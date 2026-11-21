package com.erolitechnologies.bizflow.service;

import com.erolitechnologies.bizflow.domain.Money;
import com.erolitechnologies.bizflow.infra.AppException;
import com.erolitechnologies.bizflow.infra.Db;
import com.erolitechnologies.bizflow.security.Permission;
import com.erolitechnologies.bizflow.security.Session;

import java.math.BigDecimal;
import java.util.List;

public final class CustomerService {
    public record Customer(long id, String name, String phone, String email, String address, boolean creditEnabled,
                           BigDecimal creditLimit, BigDecimal balance, boolean active) {}

    public record StatementLine(String date, String type, String reference, BigDecimal debit, BigDecimal credit, BigDecimal runningBalance) {}

    private final Db db;

    public CustomerService(Db db) { this.db = db; }

    private static Customer map(java.sql.ResultSet rs, boolean finance) throws java.sql.SQLException {
        return new Customer(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6) == 1,
                finance ? Money.fromMinor(rs.getLong(7)) : null, finance ? Money.fromMinor(rs.getLong(8)) : null, rs.getInt(9) == 1);
    }

    /** Balances and limits are only returned to users with CUSTOMER_FINANCE_VIEW. */
    public List<Customer> search(Session s, String text, boolean includeInactive) {
        s.require(Permission.CUSTOMER_VIEW);
        boolean fin = s.has(Permission.CUSTOMER_FINANCE_VIEW);
        String like = "%" + (text == null ? "" : text.trim()) + "%";
        return db.read(c -> Db.query(c,
                "SELECT id, name, COALESCE(phone,''), COALESCE(email,''), COALESCE(address,''), credit_enabled, credit_limit_minor, balance_minor, active " +
                "FROM customers WHERE (name LIKE ? OR phone LIKE ? OR email LIKE ?) AND (active = 1 OR ? = 1) ORDER BY name LIMIT 1000",
                rs -> map(rs, fin), like, like, like, includeInactive ? 1 : 0));
    }

    public long create(Session s, String name, String phone, String email, String address, boolean credit, BigDecimal limit) {
        s.require(Permission.CUSTOMER_MANAGE);
        Validate.required(name, "Customer name");
        Validate.phone(phone);
        Validate.email(email);
        BigDecimal lim = limit == null ? BigDecimal.ZERO : Validate.nonNegative(limit, "Credit limit");
        if ((credit || lim.signum() > 0) && !s.has(Permission.CUSTOMER_FINANCE_VIEW))
            throw new com.erolitechnologies.bizflow.security.PermissionDeniedException(Permission.CUSTOMER_FINANCE_VIEW);
        return db.tx(c -> {
            long id = Db.insert(c, "INSERT INTO customers(name, phone, email, address, credit_enabled, credit_limit_minor) VALUES (?,?,?,?,?,?)",
                    name.trim(), Validate.optional(phone), Validate.optional(email), Validate.optional(address), credit, Money.toMinor(lim));
            AuditService.log(c, s, "CUSTOMER_CREATED", "customer", id, name.trim());
            return id;
        });
    }

    public void update(Session s, long id, String name, String phone, String email, String address, boolean active) {
        s.require(Permission.CUSTOMER_MANAGE);
        Validate.required(name, "Customer name");
        Validate.phone(phone);
        Validate.email(email);
        db.txVoid(c -> {
            Db.update(c, "UPDATE customers SET name=?, phone=?, email=?, address=?, active=? WHERE id=?", name.trim(), Validate.optional(phone), Validate.optional(email), Validate.optional(address), active, id);
            AuditService.log(c, s, "CUSTOMER_UPDATED", "customer", id, null);
            return null;
        });
    }

    public void setCredit(Session s, long id, boolean enabled, BigDecimal limit) {
        s.require(Permission.CUSTOMER_FINANCE_VIEW);
        s.require(Permission.CUSTOMER_MANAGE);
        Validate.nonNegative(limit, "Credit limit");
        db.txVoid(c -> {
            Db.update(c, "UPDATE customers SET credit_enabled=?, credit_limit_minor=? WHERE id=?", enabled, Money.toMinor(limit), id);
            AuditService.log(c, s, "CUSTOMER_CREDIT_CHANGED", "customer", id, "enabled=" + enabled + " limit=" + Money.round(limit));
            return null;
        });
    }

    /** Customer pays off part of their account balance. */
    public void receivePayment(Session s, long customerId, BigDecimal amount, String method, String reference) {
        s.require(Permission.CUSTOMER_FINANCE_VIEW);
        Validate.positive(amount, "Payment amount");
        Validate.required(method, "Payment method");
        db.txVoid(c -> {
            long bal = Db.scalarLong(c, "SELECT balance_minor FROM customers WHERE id = ?", customerId);
            long pay = Money.toMinor(amount);
            if (pay > bal) throw new AppException("The payment is more than the amount owed (" + Money.fromMinor(bal) + ").");
            Long shift = ShiftService.openShiftId(c, s.userId());
            Db.insert(c, "INSERT INTO payments(customer_id, shift_id, method, direction, amount_minor, reference) VALUES (?,?,?,'IN',?,?)",
                    customerId, shift, method.trim(), pay, "Account payment" + (reference == null || reference.isBlank() ? "" : ": " + reference.trim()));
            Db.update(c, "UPDATE customers SET balance_minor = balance_minor - ? WHERE id = ?", pay, customerId);
            AuditService.log(c, s, "CUSTOMER_PAYMENT", "customer", customerId, "amount=" + Money.round(amount) + " method=" + method.trim());
            return null;
        });
    }

    public List<StatementLine> statement(Session s, long customerId, String from, String to) {
        s.require(Permission.CUSTOMER_FINANCE_VIEW);
        return db.read(c -> {
            // charges: credit portion of sales; payments: account payments and credit refunds
            List<Object[]> events = new java.util.ArrayList<>(Db.query(c,
                    "SELECT p.created_at, 'Sale on credit', COALESCE(s.receipt_no,''), p.amount_minor, 0 FROM payments p JOIN sales s ON s.id = p.sale_id " +
                    "WHERE p.customer_id = ? AND p.method = 'Customer Credit' AND p.direction = 'IN' " +
                    "UNION ALL SELECT p.created_at, CASE WHEN p.reference = 'Void' THEN 'Sale voided' ELSE 'Refund to account' END, COALESCE(s.receipt_no,''), 0, p.amount_minor FROM payments p JOIN sales s ON s.id = p.sale_id " +
                    "WHERE p.customer_id = ? AND p.method = 'Customer Credit' AND p.direction = 'OUT' " +
                    "UNION ALL SELECT p.created_at, 'Payment received', COALESCE(p.reference,''), 0, p.amount_minor FROM payments p " +
                    "WHERE p.customer_id = ? AND p.sale_id IS NULL AND p.direction = 'IN' ORDER BY 1",
                    rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5)}, customerId, customerId, customerId));
            List<StatementLine> out = new java.util.ArrayList<>();
            long running = 0;
            for (Object[] e : events) {
                running += (Long) e[3] - (Long) e[4];
                String date = ((String) e[0]).substring(0, 10);
                if (from != null && date.compareTo(from) < 0) continue;
                if (to != null && date.compareTo(to) > 0) continue;
                out.add(new StatementLine((String) e[0], (String) e[1], (String) e[2], Money.fromMinor((Long) e[3]), Money.fromMinor((Long) e[4]), Money.fromMinor(running)));
            }
            return out;
        });
    }

    public record PurchaseHistory(String date, String receiptNo, BigDecimal total, String status) {}

    public List<PurchaseHistory> purchaseHistory(Session s, long customerId) {
        s.require(Permission.CUSTOMER_FINANCE_VIEW);
        return db.read(c -> Db.query(c, "SELECT created_at, receipt_no, total_minor, status FROM sales WHERE customer_id = ? ORDER BY id DESC LIMIT 500",
                rs -> new PurchaseHistory(rs.getString(1), rs.getString(2), Money.fromMinor(rs.getLong(3)), rs.getString(4)), customerId));
    }
}
