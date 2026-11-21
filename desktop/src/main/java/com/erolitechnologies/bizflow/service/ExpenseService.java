package com.erolitechnologies.bizflow.service;

import com.erolitechnologies.bizflow.domain.Money;
import com.erolitechnologies.bizflow.infra.AppException;
import com.erolitechnologies.bizflow.infra.Db;
import com.erolitechnologies.bizflow.security.Permission;
import com.erolitechnologies.bizflow.security.Session;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class ExpenseService {
    public static final List<String> DEFAULT_CATEGORIES = List.of("Rent", "Utilities", "Salaries", "Transport", "Marketing", "Maintenance", "Supplies", "Taxes & Licences", "Other");

    public record Expense(long id, String category, String description, BigDecimal amount, String method, String date, String by) {}

    private final Db db;

    public ExpenseService(Db db) { this.db = db; }

    public long add(Session s, String category, String description, BigDecimal amount, String method, LocalDate date) {
        s.require(Permission.EXPENSE_MANAGE);
        Validate.required(category, "Category");
        Validate.positive(amount, "Amount");
        if (date == null) throw new AppException("Date is required.");
        if (date.isAfter(LocalDate.now().plusDays(1))) throw new AppException("Expense date cannot be in the future.");
        return db.tx(c -> {
            long id = Db.insert(c, "INSERT INTO expenses(branch_id, category, description, amount_minor, method, incurred_on, created_by) VALUES (?,?,?,?,?,?,?)",
                    s.branchId(), category.trim(), Validate.optional(description), Money.toMinor(amount), method == null || method.isBlank() ? "Cash" : method.trim(), date.toString(), s.userId());
            AuditService.log(c, s, "EXPENSE_RECORDED", "expense", id, category.trim() + " " + Money.round(amount));
            return id;
        });
    }

    /** Expenses are never edited in place; a correction is deleted with a reason in the audit trail. */
    public void delete(Session s, long id, String reason) {
        s.require(Permission.EXPENSE_MANAGE);
        Validate.required(reason, "Reason");
        db.txVoid(c -> {
            String d = Db.queryOne(c, "SELECT category || ' ' || amount_minor FROM expenses WHERE id = ?", rs -> rs.getString(1), id);
            if (d == null) throw new AppException("Expense not found.");
            Db.update(c, "DELETE FROM expenses WHERE id = ?", id);
            AuditService.log(c, s, "EXPENSE_DELETED", "expense", id, d + ", reason=" + reason.trim());
            return null;
        });
    }

    public List<Expense> list(Session s, String from, String to) {
        s.require(Permission.EXPENSE_MANAGE);
        return db.read(c -> Db.query(c,
                "SELECT e.id, e.category, COALESCE(e.description,''), e.amount_minor, e.method, e.incurred_on, COALESCE(u.username,'') FROM expenses e LEFT JOIN users u ON u.id = e.created_by " +
                "WHERE e.incurred_on >= ? AND e.incurred_on <= ? ORDER BY e.incurred_on DESC, e.id DESC LIMIT 5000",
                rs -> new Expense(rs.getLong(1), rs.getString(2), rs.getString(3), Money.fromMinor(rs.getLong(4)), rs.getString(5), rs.getString(6), rs.getString(7)),
                from == null ? "0000-01-01" : from, to == null ? "9999-12-31" : to));
    }

    public List<String> categories() {
        return db.read(c -> {
            java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>(DEFAULT_CATEGORIES);
            set.addAll(Db.query(c, "SELECT DISTINCT category FROM expenses ORDER BY 1", rs -> rs.getString(1)));
            return List.copyOf(set);
        });
    }
}
