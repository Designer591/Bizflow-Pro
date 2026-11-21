package com.erolitechnologies.bizflow.service;

import com.erolitechnologies.bizflow.domain.Money;
import com.erolitechnologies.bizflow.infra.Db;
import com.erolitechnologies.bizflow.security.Permission;
import com.erolitechnologies.bizflow.security.Session;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Accounting definitions (kept deliberately explicit):
 *  revenue      = value of non-voided sales excluding tax, minus refunds (excluding tax)
 *  COGS         = cost of goods on non-voided sales, minus cost of refunded goods returned to stock
 *  gross profit = revenue - COGS
 *  net profit   = gross profit - operating expenses
 *  cash received = money actually taken in (all methods except customer credit) minus money paid out for refunds/voids
 * Sales on customer credit count as revenue immediately but not as cash received until the customer pays.
 */
public final class FinanceService {
    public record Summary(String from, String to, BigDecimal grossSales, BigDecimal taxCollected, BigDecimal refunds, BigDecimal revenue,
                          BigDecimal cogs, BigDecimal grossProfit, BigDecimal expenses, BigDecimal netProfit,
                          BigDecimal cashReceived, int transactions, BigDecimal receivables, BigDecimal payables) {}

    private final Db db;

    public FinanceService(Db db) { this.db = db; }

    public Summary summary(Session s, String from, String to) {
        s.require(Permission.FINANCE_VIEW);
        return db.read(c -> compute(c, from, to));
    }

    public static Summary compute(Connection c, String from, String to) throws SQLException {
        long[] sales = Db.queryOne(c,
                "SELECT COALESCE(SUM(total_minor),0), COALESCE(SUM(tax_minor),0), COALESCE(SUM(cogs_minor),0), COUNT(*) FROM sales " +
                "WHERE status <> 'VOIDED' AND date(created_at) >= ? AND date(created_at) <= ?",
                rs -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)}, from, to);
        long[] ref = Db.queryOne(c,
                "SELECT COALESCE(SUM(amount_minor),0), COALESCE(SUM(tax_minor),0), COALESCE(SUM(cogs_minor),0) FROM refunds " +
                "WHERE date(created_at) >= ? AND date(created_at) <= ?", rs -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)}, from, to);
        long expenses = Db.scalarLong(c, "SELECT COALESCE(SUM(amount_minor),0) FROM expenses WHERE incurred_on >= ? AND incurred_on <= ?", from, to);
        long cash = Db.scalarLong(c,
                "SELECT COALESCE(SUM(CASE WHEN direction='IN' THEN amount_minor ELSE -amount_minor END),0) FROM payments " +
                "WHERE purchase_id IS NULL AND method <> 'Customer Credit' AND date(created_at) >= ? AND date(created_at) <= ?", from, to);
        long recv = Db.scalarLong(c, "SELECT COALESCE(SUM(balance_minor),0) FROM customers");
        long pay = Db.scalarLong(c, "SELECT COALESCE(SUM(total_minor - paid_minor),0) FROM purchases WHERE status IN ('RECEIVED','PARTIAL')");

        long revenue = (sales[0] - sales[1]) - (ref[0] - ref[1]);
        long cogs = sales[2] - ref[2];
        long gross = revenue - cogs;
        return new Summary(from, to, Money.fromMinor(sales[0]), Money.fromMinor(sales[1] - ref[1]), Money.fromMinor(ref[0]), Money.fromMinor(revenue),
                Money.fromMinor(cogs), Money.fromMinor(gross), Money.fromMinor(expenses), Money.fromMinor(gross - expenses),
                Money.fromMinor(cash), (int) sales[3], Money.fromMinor(recv), Money.fromMinor(pay));
    }
}
