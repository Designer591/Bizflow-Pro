package com.erolitechnologies.bizflow.service;

import com.erolitechnologies.bizflow.domain.Money;
import com.erolitechnologies.bizflow.infra.AppException;
import com.erolitechnologies.bizflow.infra.Db;
import com.erolitechnologies.bizflow.security.Permission;
import com.erolitechnologies.bizflow.security.Session;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/** Purchase orders, goods received (full or partial), supplier invoices and payments. Stock updates only on receipt. */
public final class PurchaseService {
    public record OrderLine(long productId, BigDecimal qty, BigDecimal unitCost) {}

    public record Order(long id, String poNumber, long supplierId, String supplier, String status, String invoiceNumber,
                        BigDecimal total, BigDecimal paid, String orderedAt, String receivedAt) {}

    public record Item(long id, long productId, String product, BigDecimal qty, BigDecimal received, BigDecimal unitCost) {}

    private final Db db;

    public PurchaseService(Db db) { this.db = db; }

    public long createOrder(Session s, long supplierId, List<OrderLine> lines, String invoiceNumber) {
        s.require(Permission.PURCHASE_MANAGE);
        if (lines == null || lines.isEmpty()) throw new AppException("Add at least one product to the purchase order.");
        return db.tx(c -> {
            if (Db.scalarLong(c, "SELECT COUNT(*) FROM suppliers WHERE id = ? AND active = 1", supplierId) == 0) throw new AppException("Choose an active supplier.");
            long total = 0;
            for (OrderLine l : lines) {
                Validate.positive(l.qty(), "Quantity");
                Validate.nonNegative(l.unitCost(), "Unit cost");
                total += l.unitCost().multiply(l.qty()).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
            }
            long id = Db.insert(c, "INSERT INTO purchases(branch_id, supplier_id, po_number, invoice_number, status, total_minor, created_by) VALUES (?,?,?,?,'ORDERED',?,?)",
                    s.branchId(), supplierId, "TMP-" + System.nanoTime(), Validate.optional(invoiceNumber), total, s.userId());
            Db.update(c, "UPDATE purchases SET po_number = ? WHERE id = ?", String.format("PO-%06d", id), id);
            for (OrderLine l : lines)
                Db.insert(c, "INSERT INTO purchase_items(purchase_id, product_id, qty_milli, unit_cost_minor) VALUES (?,?,?,?)", id, l.productId(), Money.qtyToMilli(l.qty()), Money.toMinor(l.unitCost()));
            AuditService.log(c, s, "PURCHASE_ORDER_CREATED", "purchase", id, "total=" + Money.fromMinor(total));
            return id;
        });
    }

    /** @param receivedNow purchase_item id -> quantity received now. Updates stock, product cost (latest cost) and order status. */
    public void receive(Session s, long purchaseId, Map<Long, BigDecimal> receivedNow, String invoiceNumber) {
        s.require(Permission.PURCHASE_MANAGE);
        if (receivedNow == null || receivedNow.isEmpty()) throw new AppException("Enter the quantities received.");
        db.txVoid(c -> {
            String status = Db.queryOne(c, "SELECT status FROM purchases WHERE id = ?", rs -> rs.getString(1), purchaseId);
            if (status == null) throw new AppException("Purchase order not found.");
            if (status.equals("RECEIVED") || status.equals("CANCELLED")) throw new AppException("This purchase order is already " + status.toLowerCase() + ".");
            for (var e : receivedNow.entrySet()) {
                BigDecimal q = e.getValue();
                if (q == null || q.signum() == 0) continue;
                Validate.positive(q, "Received quantity");
                long qMilli = Money.qtyToMilli(q);
                long[] it = Db.queryOne(c, "SELECT product_id, qty_milli, received_milli, unit_cost_minor FROM purchase_items WHERE id = ? AND purchase_id = ?",
                        rs -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)}, e.getKey(), purchaseId);
                if (it == null) throw new AppException("That item is not on this purchase order.");
                if (it[2] + qMilli > it[1]) throw new AppException("You cannot receive more than was ordered.");
                Db.update(c, "UPDATE purchase_items SET received_milli = received_milli + ? WHERE id = ?", qMilli, e.getKey());
                StockService.apply(c, s.branchId(), it[0], q, "PURCHASE", "purchase", purchaseId, "PO " + purchaseId, s.userId(), true);
                Db.update(c, "UPDATE products SET cost_minor = ? WHERE id = ?", it[3], it[0]);
            }
            boolean all = Db.scalarLong(c, "SELECT COUNT(*) FROM purchase_items WHERE purchase_id = ? AND received_milli < qty_milli", purchaseId) == 0;
            boolean any = Db.scalarLong(c, "SELECT COALESCE(SUM(received_milli),0) FROM purchase_items WHERE purchase_id = ?", purchaseId) > 0;
            Db.update(c, "UPDATE purchases SET status = ?, received_at = CASE WHEN ? = 1 THEN datetime('now','localtime') ELSE received_at END, invoice_number = COALESCE(?, invoice_number) WHERE id = ?",
                    all ? "RECEIVED" : any ? "PARTIAL" : status, all, Validate.optional(invoiceNumber), purchaseId);
            AuditService.log(c, s, "GOODS_RECEIVED", "purchase", purchaseId, all ? "complete" : "partial");
            return null;
        });
    }

    public void cancel(Session s, long purchaseId, String reason) {
        s.require(Permission.PURCHASE_MANAGE);
        Validate.required(reason, "Reason");
        db.txVoid(c -> {
            String st = Db.queryOne(c, "SELECT status FROM purchases WHERE id = ?", rs -> rs.getString(1), purchaseId);
            if (!"ORDERED".equals(st)) throw new AppException("Only orders that have not received any goods can be cancelled.");
            Db.update(c, "UPDATE purchases SET status = 'CANCELLED' WHERE id = ?", purchaseId);
            AuditService.log(c, s, "PURCHASE_CANCELLED", "purchase", purchaseId, reason.trim());
            return null;
        });
    }

    public void paySupplier(Session s, long purchaseId, BigDecimal amount, String method, String reference) {
        s.require(Permission.SUPPLIER_FINANCE_VIEW);
        Validate.positive(amount, "Payment amount");
        Validate.required(method, "Payment method");
        db.txVoid(c -> {
            long[] t = Db.queryOne(c, "SELECT total_minor, paid_minor FROM purchases WHERE id = ? AND status <> 'CANCELLED'", rs -> new long[]{rs.getLong(1), rs.getLong(2)}, purchaseId);
            if (t == null) throw new AppException("Purchase order not found.");
            long pay = Money.toMinor(amount);
            if (t[1] + pay > t[0]) throw new AppException("This payment is more than the amount still owed (" + Money.fromMinor(t[0] - t[1]) + ").");
            Db.insert(c, "INSERT INTO payments(purchase_id, method, direction, amount_minor, reference) VALUES (?,?,'OUT',?,?)", purchaseId, method.trim(), pay, Validate.optional(reference));
            Db.update(c, "UPDATE purchases SET paid_minor = paid_minor + ? WHERE id = ?", pay, purchaseId);
            AuditService.log(c, s, "SUPPLIER_PAYMENT", "purchase", purchaseId, "amount=" + Money.fromMinor(pay));
            return null;
        });
    }

    public List<Order> list(Session s, String status) {
        s.require(Permission.PURCHASE_MANAGE);
        boolean fin = s.has(Permission.SUPPLIER_FINANCE_VIEW);
        return db.read(c -> Db.query(c,
                "SELECT p.id, p.po_number, p.supplier_id, su.name, p.status, COALESCE(p.invoice_number,''), p.total_minor, p.paid_minor, p.ordered_at, COALESCE(p.received_at,'') " +
                "FROM purchases p JOIN suppliers su ON su.id = p.supplier_id WHERE (? = '' OR p.status = ?) ORDER BY p.id DESC LIMIT 1000",
                rs -> new Order(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        Money.fromMinor(rs.getLong(7)), fin ? Money.fromMinor(rs.getLong(8)) : null, rs.getString(9), rs.getString(10)),
                status == null ? "" : status, status == null ? "" : status));
    }

    public List<Item> items(Session s, long purchaseId) {
        s.require(Permission.PURCHASE_MANAGE);
        return db.read(c -> Db.query(c, "SELECT i.id, i.product_id, p.name, i.qty_milli, i.received_milli, i.unit_cost_minor FROM purchase_items i JOIN products p ON p.id = i.product_id WHERE i.purchase_id = ?",
                rs -> new Item(rs.getLong(1), rs.getLong(2), rs.getString(3), Money.qtyFromMilli(rs.getLong(4)), Money.qtyFromMilli(rs.getLong(5)), Money.fromMinor(rs.getLong(6))), purchaseId));
    }
}
