package com.erolitechnologies.bizflow.service;

import com.erolitechnologies.bizflow.domain.Money;
import com.erolitechnologies.bizflow.infra.AppException;
import com.erolitechnologies.bizflow.infra.Db;
import com.erolitechnologies.bizflow.security.Permission;
import com.erolitechnologies.bizflow.security.Session;

import java.math.BigDecimal;
import java.util.List;

public final class SupplierService {
    public record Supplier(long id, String name, String contact, String phone, String email, String address, boolean active, BigDecimal balance) {}

    public record Transaction(String date, String type, String reference, BigDecimal amount) {}

    private final Db db;

    public SupplierService(Db db) { this.db = db; }

    /** Balance = what we owe: purchase totals for received orders minus payments made. Hidden without SUPPLIER_FINANCE_VIEW. */
    public List<Supplier> search(Session s, String text, boolean includeInactive) {
        s.require(Permission.SUPPLIER_MANAGE);
        boolean fin = s.has(Permission.SUPPLIER_FINANCE_VIEW);
        String like = "%" + (text == null ? "" : text.trim()) + "%";
        return db.read(c -> Db.query(c,
                "SELECT s.id, s.name, COALESCE(s.contact_name,''), COALESCE(s.phone,''), COALESCE(s.email,''), COALESCE(s.address,''), s.active, " +
                "COALESCE((SELECT SUM(total_minor - paid_minor) FROM purchases WHERE supplier_id = s.id AND status IN ('RECEIVED','PARTIAL')),0) " +
                "FROM suppliers s WHERE (s.name LIKE ? OR s.contact_name LIKE ?) AND (s.active = 1 OR ? = 1) ORDER BY s.name",
                rs -> new Supplier(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getInt(7) == 1,
                        fin ? Money.fromMinor(rs.getLong(8)) : null), like, like, includeInactive ? 1 : 0));
    }

    public long create(Session s, String name, String contact, String phone, String email, String address) {
        s.require(Permission.SUPPLIER_MANAGE);
        Validate.required(name, "Supplier name");
        Validate.phone(phone);
        Validate.email(email);
        return db.tx(c -> {
            long id = Db.insert(c, "INSERT INTO suppliers(name, contact_name, phone, email, address) VALUES (?,?,?,?,?)", name.trim(), Validate.optional(contact), Validate.optional(phone), Validate.optional(email), Validate.optional(address));
            AuditService.log(c, s, "SUPPLIER_CREATED", "supplier", id, name.trim());
            return id;
        });
    }

    public void update(Session s, long id, String name, String contact, String phone, String email, String address, boolean active) {
        s.require(Permission.SUPPLIER_MANAGE);
        Validate.required(name, "Supplier name");
        Validate.phone(phone);
        Validate.email(email);
        db.txVoid(c -> {
            Db.update(c, "UPDATE suppliers SET name=?, contact_name=?, phone=?, email=?, address=?, active=? WHERE id=?", name.trim(), Validate.optional(contact), Validate.optional(phone), Validate.optional(email), Validate.optional(address), active, id);
            AuditService.log(c, s, "SUPPLIER_UPDATED", "supplier", id, null);
            return null;
        });
    }

    public List<Transaction> history(Session s, long supplierId) {
        s.require(Permission.SUPPLIER_FINANCE_VIEW);
        return db.read(c -> Db.query(c,
                "SELECT ordered_at, 'Purchase order (' || status || ')', po_number || COALESCE(' / inv ' || invoice_number, ''), total_minor FROM purchases WHERE supplier_id = ? " +
                "UNION ALL SELECT created_at, 'Payment made', COALESCE(reference,''), amount_minor FROM payments WHERE purchase_id IN (SELECT id FROM purchases WHERE supplier_id = ?) AND direction = 'OUT' ORDER BY 1 DESC",
                rs -> new Transaction(rs.getString(1), rs.getString(2), rs.getString(3), Money.fromMinor(rs.getLong(4))), supplierId, supplierId));
    }
}
