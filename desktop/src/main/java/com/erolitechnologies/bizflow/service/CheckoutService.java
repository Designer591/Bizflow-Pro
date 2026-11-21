package com.erolitechnologies.bizflow.service;

import com.erolitechnologies.bizflow.domain.Money;
import com.erolitechnologies.bizflow.domain.sale.*;
import com.erolitechnologies.bizflow.infra.AppException;
import com.erolitechnologies.bizflow.infra.Db;
import com.erolitechnologies.bizflow.security.Permission;
import com.erolitechnologies.bizflow.security.Session;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Completes a sale in ONE database transaction: validate stock and prices (prices always come from the
 * database, never from the screen), calculate totals, record sale/items/payments, deduct stock, write
 * stock movements and audit trail. Any failure rolls everything back.
 */
public final class CheckoutService {
    public record Line(long productId, BigDecimal qty, BigDecimal lineDiscount) {}

    public record Request(List<Line> lines, BigDecimal cartDiscount, Long customerId, List<PaymentLine> payments) {}

    public record Result(long saleId, String receiptNo, SaleTotals totals, BigDecimal paid, BigDecimal change) {}

    private final Db db;

    public CheckoutService(Db db) { this.db = db; }

    private record ProductRow(long id, String name, long priceMinor, long costMinor, boolean taxExempt, boolean archived) {}

    /** Price a cart without saving anything (used live by the till screen). */
    public SaleTotals preview(Session s, List<Line> lines, BigDecimal cartDiscount) {
        s.require(Permission.POS_SELL);
        return db.read(c -> price(c, lines, cartDiscount, loadProducts(c, lines)));
    }

    public Result checkout(Session s, Request req) {
        s.require(Permission.POS_SELL);
        if (req == null || req.lines() == null || req.lines().isEmpty()) throw new AppException("The cart is empty.");
        return db.tx(c -> {
            Map<Long, ProductRow> products = loadProducts(c, req.lines());
            SaleTotals totals = price(c, req.lines(), req.cartDiscount(), products);

            // discount authorisation
            if (totals.discount().signum() > 0) {
                s.require(Permission.POS_DISCOUNT);
                BigDecimal limit = SettingsService.getDecimal(c, "max_discount_percent_cashier");
                BigDecimal pct = totals.subtotal().signum() == 0 ? BigDecimal.ZERO
                        : totals.discount().multiply(new BigDecimal("100")).divide(totals.subtotal(), 4, java.math.RoundingMode.HALF_UP);
                if (pct.compareTo(limit) > 0 && !s.has(Permission.POS_DISCOUNT_LARGE))
                    throw new AppException("Discounts above " + limit.stripTrailingZeros().toPlainString() + "% need manager approval.");
            }

            // customer & credit
            Long customerId = req.customerId();
            long creditLimit = 0, creditBalance = 0;
            boolean creditAllowed = false;
            if (customerId != null) {
                Object[] cust = Db.queryOne(c, "SELECT credit_enabled, credit_limit_minor, balance_minor, active FROM customers WHERE id = ?",
                        rs -> new Object[]{rs.getInt(1) == 1, rs.getLong(2), rs.getLong(3), rs.getInt(4) == 1}, customerId);
                if (cust == null) throw new AppException("Customer not found.");
                if (!(Boolean) cust[3]) throw new AppException("This customer account is inactive.");
                creditAllowed = (Boolean) cust[0];
                creditLimit = (Long) cust[1];
                creditBalance = (Long) cust[2];
            }
            PaymentAllocator.Result pay;
            try {
                pay = PaymentAllocator.allocate(totals.total(), req.payments(), creditAllowed);
            } catch (IllegalArgumentException e) {
                throw new AppException(e.getMessage(), e);
            }
            if (pay.creditAmount().signum() > 0) {
                if (!s.has(Permission.CUSTOMER_VIEW)) throw new com.erolitechnologies.bizflow.security.PermissionDeniedException(Permission.CUSTOMER_VIEW);
                if (creditBalance + Money.toMinor(pay.creditAmount()) > creditLimit)
                    throw new AppException("This sale would take the customer over their credit limit of " + Money.fromMinor(creditLimit) + ".");
            }

            // shift
            Long shiftId = ShiftService.openShiftId(c, s.userId());
            if (shiftId == null && SettingsService.getBool(c, "require_open_shift"))
                throw new AppException("Open your cash shift before making sales.");

            // sale header (receipt number is derived from the unique row id, so it can never collide)
            long saleId = Db.insert(c,
                    "INSERT INTO sales(branch_id, receipt_no, customer_id, cashier_id, shift_id, subtotal_minor, discount_minor, tax_minor, total_minor, cogs_minor) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    s.branchId(), "TMP-" + UUID.randomUUID(), customerId, s.userId(), shiftId, Money.toMinor(totals.subtotal()), Money.toMinor(totals.discount()),
                    Money.toMinor(totals.tax()), Money.toMinor(totals.total()), Money.toMinor(totals.cogs()));
            String prefix = SettingsService.get(c, "receipt_prefix");
            String receiptNo = (prefix.isBlank() ? "RCP" : prefix) + "-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + String.format("%06d", saleId);
            Db.update(c, "UPDATE sales SET receipt_no = ? WHERE id = ?", receiptNo, saleId);

            boolean allowNegative = SettingsService.getBool(c, "allow_negative_stock");
            for (PricedLine pl : totals.lines()) {
                CartLine cl = pl.source();
                ProductRow p = products.get(cl.productId());
                Db.insert(c, "INSERT INTO sale_items(sale_id, product_id, description, qty_milli, unit_price_minor, unit_cost_minor, discount_minor, tax_minor, line_total_minor) VALUES (?,?,?,?,?,?,?,?,?)",
                        saleId, cl.productId(), cl.description(), Money.qtyToMilli(cl.qty()), Money.toMinor(cl.unitPrice()), Money.toMinor(cl.unitCost()),
                        Money.toMinor(pl.discount()), Money.toMinor(pl.tax()), Money.toMinor(pl.lineTotal()));
                StockService.apply(c, s.branchId(), cl.productId(), cl.qty().negate(), "SALE", "sale", saleId, receiptNo, s.userId(), allowNegative);
            }
            for (PaymentAllocator.AppliedPayment ap : pay.applied()) {
                Db.insert(c, "INSERT INTO payments(sale_id, customer_id, shift_id, method, direction, amount_minor, tendered_minor, change_minor, reference) VALUES (?,?,?,?,'IN',?,?,?,?)",
                        saleId, customerId, shiftId, ap.method(), Money.toMinor(ap.applied()), Money.toMinor(ap.tendered()), Money.toMinor(ap.change()), Validate.optional(ap.reference()));
            }
            if (pay.creditAmount().signum() > 0)
                Db.update(c, "UPDATE customers SET balance_minor = balance_minor + ? WHERE id = ?", Money.toMinor(pay.creditAmount()), customerId);
            AuditService.log(c, s, "SALE_COMPLETED", "sale", saleId, receiptNo + " total=" + totals.total());
            return new Result(saleId, receiptNo, totals, pay.paid(), pay.change());
        });
    }

    private Map<Long, ProductRow> loadProducts(Connection c, List<Line> lines) throws SQLException {
        Map<Long, ProductRow> map = new HashMap<>();
        for (Line l : lines) {
            if (map.containsKey(l.productId())) continue;
            ProductRow p = Db.queryOne(c, "SELECT id, name, price_minor, cost_minor, tax_exempt, archived FROM products WHERE id = ?",
                    rs -> new ProductRow(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getInt(5) == 1, rs.getInt(6) == 1), l.productId());
            if (p == null) throw new AppException("A product in the cart no longer exists.");
            if (p.archived()) throw new AppException("\"" + p.name() + "\" has been archived and cannot be sold.");
            map.put(p.id(), p);
        }
        return map;
    }

    private SaleTotals price(Connection c, List<Line> lines, BigDecimal cartDiscount, Map<Long, ProductRow> products) throws SQLException {
        if (lines == null || lines.isEmpty()) throw new AppException("The cart is empty.");
        List<CartLine> cart = new ArrayList<>();
        for (Line l : lines) {
            ProductRow p = products.get(l.productId());
            if (l.qty() == null || l.qty().signum() <= 0) throw new AppException("Quantity for \"" + p.name() + "\" must be greater than zero.");
            if (l.qty().scale() > 3 && l.qty().stripTrailingZeros().scale() > 3) throw new AppException("Quantity for \"" + p.name() + "\" has too many decimals.");
            try {
                cart.add(new CartLine(p.id(), p.name(), l.qty(), Money.fromMinor(p.priceMinor()), Money.fromMinor(p.costMinor()), p.taxExempt(),
                        l.lineDiscount() == null ? BigDecimal.ZERO : l.lineDiscount()));
            } catch (IllegalArgumentException e) {
                throw new AppException(e.getMessage(), e);
            }
        }
        boolean taxOn = SettingsService.getBool(c, "tax_enabled");
        BigDecimal rate = taxOn ? SettingsService.getDecimal(c, "tax_rate") : BigDecimal.ZERO;
        boolean inclusive = SettingsService.getBool(c, "tax_inclusive");
        try {
            return new SaleCalculator(rate, inclusive).calculate(cart, cartDiscount);
        } catch (IllegalArgumentException e) {
            throw new AppException(e.getMessage(), e);
        }
    }
}
