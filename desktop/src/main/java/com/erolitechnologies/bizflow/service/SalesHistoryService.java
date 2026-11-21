package com.erolitechnologies.bizflow.service;

import com.erolitechnologies.bizflow.domain.Money;
import com.erolitechnologies.bizflow.infra.AppException;
import com.erolitechnologies.bizflow.infra.Db;
import com.erolitechnologies.bizflow.security.Permission;
import com.erolitechnologies.bizflow.security.Session;

import java.math.BigDecimal;
import java.util.List;

public final class SalesHistoryService {
    public record SaleRow(long id, String receiptNo, String when, String cashier, String customer, BigDecimal total, String status) {}
    public record ItemRow(long id, String description, BigDecimal qty, BigDecimal refundedQty, BigDecimal unitPrice, BigDecimal lineTotal) {}

    private final Db db;

    public SalesHistoryService(Db db) { this.db = db; }

    /** Cashiers see only their own sales; users with SALES_HISTORY_ALL see everything. */
    public List<SaleRow> search(Session s, String text, String from, String to) {
        s.require(Permission.SALES_HISTORY_VIEW);
        boolean all = s.has(Permission.SALES_HISTORY_ALL);
        String like = "%" + (text == null ? "" : text.trim()) + "%";
        return db.read(c -> Db.query(c,
                "SELECT s.id, s.receipt_no, s.created_at, u.full_name, COALESCE(cu.name,''), s.total_minor, s.status FROM sales s JOIN users u ON u.id = s.cashier_id " +
                "LEFT JOIN customers cu ON cu.id = s.customer_id WHERE (s.receipt_no LIKE ? OR cu.name LIKE ?) AND date(s.created_at) >= ? AND date(s.created_at) <= ? " +
                "AND (? = 1 OR s.cashier_id = ?) ORDER BY s.id DESC LIMIT 1000",
                rs -> new SaleRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), Money.fromMinor(rs.getLong(6)), rs.getString(7)),
                like, like, from == null ? "0000-01-01" : from, to == null ? "9999-12-31" : to, all ? 1 : 0, s.userId()));
    }

    public List<ItemRow> items(Session s, long saleId) {
        s.require(Permission.SALES_HISTORY_VIEW);
        boolean all = s.has(Permission.SALES_HISTORY_ALL);
        return db.read(c -> {
            if (!all && Db.scalarLong(c, "SELECT COUNT(*) FROM sales WHERE id = ? AND cashier_id = ?", saleId, s.userId()) == 0)
                throw new AppException("You can only view your own sales.");
            return Db.query(c, "SELECT id, description, qty_milli, refunded_milli, unit_price_minor, line_total_minor FROM sale_items WHERE sale_id = ?",
                    rs -> new ItemRow(rs.getLong(1), rs.getString(2), Money.qtyFromMilli(rs.getLong(3)), Money.qtyFromMilli(rs.getLong(4)), Money.fromMinor(rs.getLong(5)), Money.fromMinor(rs.getLong(6))), saleId);
        });
    }
}
