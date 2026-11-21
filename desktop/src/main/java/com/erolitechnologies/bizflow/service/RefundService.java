package com.erolitechnologies.bizflow.service;

import com.erolitechnologies.bizflow.domain.Money;
import com.erolitechnologies.bizflow.domain.sale.PaymentAllocator;
import com.erolitechnologies.bizflow.infra.AppException;
import com.erolitechnologies.bizflow.infra.Db;
import com.erolitechnologies.bizflow.security.Permission;
import com.erolitechnologies.bizflow.security.Session;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/** Refunds and voids. Completed sales are never deleted: every reversal is a new, audited record. */
public final class RefundService {
    private final Db db;

    public RefundService(Db db) { this.db = db; }

    /** @param qtyBySaleItem sale_item id -> quantity to return */
    public BigDecimal refund(Session s, long saleId, Map<Long, BigDecimal> qtyBySaleItem, String method, boolean restock, String reason) {
        s.require(Permission.POS_REFUND);
        Validate.required(reason, "Reason");
        Validate.required(method, "Refund method");
        if (qtyBySaleItem == null || qtyBySaleItem.isEmpty()) throw new AppException("Choose at least one item to refund.");
        return db.tx(c -> {
            record Sale(String status, Long customerId, String receipt) {}
            Sale sale = Db.queryOne(c, "SELECT status, customer_id, receipt_no FROM sales WHERE id = ?", rs -> new Sale(rs.getString(1), (Long) rs.getObject(2), rs.getString(3)), saleId);
            if (sale == null) throw new AppException("Sale not found.");
            if (sale.status().equals("VOIDED")) throw new AppException("This sale has been voided and cannot be refunded.");
            if (sale.status().equals("REFUNDED")) throw new AppException("This sale has already been fully refunded.");
            boolean toCredit = PaymentAllocator.CREDIT.equalsIgnoreCase(method);
            if (toCredit && sale.customerId() == null) throw new AppException("Refund to customer credit needs a customer on the sale.");

            long total = 0;
            for (var e : qtyBySaleItem.entrySet()) {
                BigDecimal qty = Validate.positive(e.getValue(), "Refund quantity");
                record Item(long productId, long soldMilli, long refundedMilli, long lineTotal, long tax, long unitCost) {}
                Item it = Db.queryOne(c, "SELECT product_id, qty_milli, refunded_milli, line_total_minor, tax_minor, unit_cost_minor FROM sale_items WHERE id = ? AND sale_id = ?",
                        rs -> new Item(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6)), e.getKey(), saleId);
                if (it == null) throw new AppException("That item is not part of this sale.");
                long qMilli = Money.qtyToMilli(qty);
                if (qMilli + it.refundedMilli() > it.soldMilli()) throw new AppException("You cannot refund more than was sold.");
                long amount, tax;
                if (qMilli + it.refundedMilli() == it.soldMilli()) { // last portion takes the remainder so cents never drift
                    long prevAmt = Db.scalarLong(c, "SELECT COALESCE(SUM(amount_minor),0) FROM refunds WHERE sale_item_id = ?", e.getKey());
                    long prevTax = Db.scalarLong(c, "SELECT COALESCE(SUM(tax_minor),0) FROM refunds WHERE sale_item_id = ?", e.getKey());
                    amount = it.lineTotal() - prevAmt;
                    tax = it.tax() - prevTax;
                } else {
                    amount = BigDecimal.valueOf(it.lineTotal()).multiply(BigDecimal.valueOf(qMilli)).divide(BigDecimal.valueOf(it.soldMilli()), 0, RoundingMode.HALF_UP).longValueExact();
                    tax = BigDecimal.valueOf(it.tax()).multiply(BigDecimal.valueOf(qMilli)).divide(BigDecimal.valueOf(it.soldMilli()), 0, RoundingMode.HALF_UP).longValueExact();
                }
                long cogs = restock ? BigDecimal.valueOf(it.unitCost()).multiply(BigDecimal.valueOf(qMilli)).divide(BigDecimal.valueOf(1000), 0, RoundingMode.HALF_UP).longValueExact() : 0;
                Db.insert(c, "INSERT INTO refunds(sale_id, sale_item_id, qty_milli, amount_minor, tax_minor, cogs_minor, method, restock, reason, approved_by) VALUES (?,?,?,?,?,?,?,?,?,?)",
                        saleId, e.getKey(), qMilli, amount, tax, cogs, method, restock, reason.trim(), s.userId());
                Db.update(c, "UPDATE sale_items SET refunded_milli = refunded_milli + ? WHERE id = ?", qMilli, e.getKey());
                if (restock) StockService.apply(c, s.branchId(), it.productId(), qty, "REFUND", "sale", saleId, sale.receipt(), s.userId(), true);
                total += amount;
            }
            boolean fully = Db.scalarLong(c, "SELECT COUNT(*) FROM sale_items WHERE sale_id = ? AND refunded_milli < qty_milli", saleId) == 0;
            Db.update(c, "UPDATE sales SET status = ? WHERE id = ?", fully ? "REFUNDED" : "PARTIALLY_REFUNDED", saleId);
            Long shift = ShiftService.openShiftId(c, s.userId());
            Db.insert(c, "INSERT INTO payments(sale_id, customer_id, shift_id, method, direction, amount_minor, reference) VALUES (?,?,?,?,'OUT',?,?)",
                    saleId, sale.customerId(), shift, method, total, "Refund");
            if (toCredit) Db.update(c, "UPDATE customers SET balance_minor = balance_minor - ? WHERE id = ?", total, sale.customerId());
            AuditService.log(c, s, "SALE_REFUNDED", "sale", saleId, sale.receipt() + " amount=" + Money.fromMinor(total) + ", reason=" + reason.trim());
            return Money.fromMinor(total);
        });
    }

    /** Reverses an entire sale that has no refunds: restores stock, reverses payments and credit. */
    public void voidSale(Session s, long saleId, String reason) {
        s.require(Permission.POS_VOID);
        Validate.required(reason, "Reason");
        db.txVoid(c -> {
            record Sale(String status, Long customerId, String receipt) {}
            Sale sale = Db.queryOne(c, "SELECT status, customer_id, receipt_no FROM sales WHERE id = ?", rs -> new Sale(rs.getString(1), (Long) rs.getObject(2), rs.getString(3)), saleId);
            if (sale == null) throw new AppException("Sale not found.");
            if (!sale.status().equals("COMPLETED")) throw new AppException("Only completed sales without refunds can be voided.");
            for (long[] it : Db.query(c, "SELECT product_id, qty_milli FROM sale_items WHERE sale_id = ?", rs -> new long[]{rs.getLong(1), rs.getLong(2)}, saleId))
                StockService.apply(c, s.branchId(), it[0], Money.qtyFromMilli(it[1]), "VOID", "sale", saleId, sale.receipt(), s.userId(), true);
            Long shift = ShiftService.openShiftId(c, s.userId());
            long creditUsed = 0;
            for (Object[] p : Db.query(c, "SELECT method, amount_minor FROM payments WHERE sale_id = ? AND direction = 'IN'", rs -> new Object[]{rs.getString(1), rs.getLong(2)}, saleId)) {
                Db.insert(c, "INSERT INTO payments(sale_id, customer_id, shift_id, method, direction, amount_minor, reference) VALUES (?,?,?,?,'OUT',?,?)",
                        saleId, sale.customerId(), shift, p[0], p[1], "Void");
                if (PaymentAllocator.CREDIT.equalsIgnoreCase((String) p[0])) creditUsed += (Long) p[1];
            }
            if (creditUsed > 0 && sale.customerId() != null)
                Db.update(c, "UPDATE customers SET balance_minor = balance_minor - ? WHERE id = ?", creditUsed, sale.customerId());
            Db.update(c, "UPDATE sales SET status='VOIDED', voided_at=datetime('now','localtime'), voided_by=?, void_reason=? WHERE id=?", s.userId(), reason.trim(), saleId);
            AuditService.log(c, s, "SALE_VOIDED", "sale", saleId, sale.receipt() + ", reason=" + reason.trim());
            return null;
        });
    }
}
