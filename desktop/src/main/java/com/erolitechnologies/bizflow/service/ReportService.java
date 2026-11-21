package com.erolitechnologies.bizflow.service;

import com.erolitechnologies.bizflow.domain.Money;
import com.erolitechnologies.bizflow.infra.AppException;
import com.erolitechnologies.bizflow.infra.Db;
import com.erolitechnologies.bizflow.report.Table;
import com.erolitechnologies.bizflow.security.Permission;
import com.erolitechnologies.bizflow.security.Session;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class ReportService {
    public enum Type {
        DAILY_SALES("Daily sales", Permission.REPORT_VIEW), WEEKLY_SALES("Weekly sales", Permission.REPORT_VIEW),
        MONTHLY_SALES("Monthly sales", Permission.REPORT_VIEW), ANNUAL_SALES("Annual sales", Permission.REPORT_VIEW),
        SALES_BY_PRODUCT("Sales by product", Permission.REPORT_VIEW), SALES_BY_CATEGORY("Sales by category", Permission.REPORT_VIEW),
        SALES_BY_EMPLOYEE("Sales by employee", Permission.REPORT_VIEW), PROFIT_AND_LOSS("Profit and loss", Permission.FINANCE_VIEW),
        EXPENSES("Expenses", Permission.FINANCE_VIEW), STOCK("Stock and valuation", Permission.REPORT_VIEW),
        LOW_STOCK("Low stock", Permission.REPORT_VIEW), CUSTOMERS("Customers", Permission.CUSTOMER_FINANCE_VIEW),
        SUPPLIERS("Suppliers", Permission.SUPPLIER_FINANCE_VIEW), CASHIER_SHIFTS("Cashier shifts", Permission.SHIFT_REVIEW);

        public final String label;
        public final Permission permission;
        Type(String label, Permission permission) { this.label = label; this.permission = permission; }
    }

    private final Db db;

    public ReportService(Db db) { this.db = db; }

    public List<Type> availableTo(Session s) {
        List<Type> out = new ArrayList<>();
        if (!s.has(Permission.REPORT_VIEW) && !s.has(Permission.FINANCE_VIEW)) return out;
        for (Type t : Type.values()) if (s.has(t.permission)) out.add(t);
        return out;
    }

    public Table run(Session s, Type type, String from, String to) {
        s.require(type.permission);
        if (from == null || to == null || from.compareTo(to) > 0) throw new AppException("Choose a valid date range.");
        return db.read(c -> build(c, s, type, from, to));
    }

    private static String m(long minor) { return Money.fromMinor(minor).toPlainString(); }
    private static String q(long milli) { return Money.qtyFromMilli(milli).stripTrailingZeros().toPlainString(); }

    private Table build(Connection c, Session s, Type t, String from, String to) throws SQLException {
        String label = t.label + " (" + from + " to " + to + ")";
        switch (t) {
            case DAILY_SALES: return periodSales(c, label, "date(created_at)", from, to, "Day");
            case WEEKLY_SALES: return periodSales(c, label, "strftime('%Y-W%W', created_at)", from, to, "Week");
            case MONTHLY_SALES: return periodSales(c, label, "strftime('%Y-%m', created_at)", from, to, "Month");
            case ANNUAL_SALES: return periodSales(c, label, "strftime('%Y', created_at)", from, to, "Year");
            case SALES_BY_PRODUCT: return grouped(c, label, "Product", "si.description", "", from, to, false);
            case SALES_BY_CATEGORY: return grouped(c, label, "Category", "COALESCE(ca.name,'Uncategorised')", "LEFT JOIN products pr ON pr.id = si.product_id LEFT JOIN categories ca ON ca.id = pr.category_id", from, to, false);
            case SALES_BY_EMPLOYEE: return byEmployee(c, label, from, to);
            case PROFIT_AND_LOSS: return pnl(c, label, from, to);
            case EXPENSES: return expenses(c, label, from, to);
            case STOCK: return stock(c, s, label, false);
            case LOW_STOCK: return stock(c, s, label, true);
            case CUSTOMERS: return customers(c, label);
            case SUPPLIERS: return suppliers(c, label);
            case CASHIER_SHIFTS: return shifts(c, label, from, to);
            default: throw new AppException("Unknown report.");
        }
    }

    private Table periodSales(Connection c, String title, String expr, String from, String to, String col) throws SQLException {
        List<long[]> raw = new ArrayList<>(); List<String> keys = new ArrayList<>();
        for (Object[] r : Db.query(c, "SELECT " + expr + ", COUNT(*), SUM(total_minor - tax_minor), SUM(discount_minor), SUM(tax_minor), SUM(total_minor), SUM(cogs_minor) FROM sales " +
                "WHERE status <> 'VOIDED' AND date(created_at) >= ? AND date(created_at) <= ? GROUP BY 1 ORDER BY 1",
                rs -> new Object[]{rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7)}, from, to)) {
            keys.add((String) r[0]);
            raw.add(new long[]{(Long) r[1], (Long) r[2], (Long) r[3], (Long) r[4], (Long) r[5], (Long) r[6]});
        }
        List<List<String>> rows = new ArrayList<>(); long[] tot = new long[6];
        for (int i = 0; i < raw.size(); i++) {
            long[] v = raw.get(i);
            for (int k = 0; k < 6; k++) tot[k] += v[k];
            rows.add(List.of(keys.get(i), String.valueOf(v[0]), m(v[1]), m(v[2]), m(v[3]), m(v[4]), m(v[1] - v[5])));
        }
        return new Table(title, List.of(col, "Sales", "Revenue (ex tax)", "Discounts", "Tax", "Total incl. tax", "Gross profit"), rows,
                List.of("TOTAL", String.valueOf(tot[0]), m(tot[1]), m(tot[2]), m(tot[3]), m(tot[4]), m(tot[1] - tot[5])),
                List.of(false, true, true, true, true, true, true));
    }

    private Table grouped(Connection c, String title, String col, String keyExpr, String join, String from, String to, boolean x) throws SQLException {
        List<List<String>> rows = new ArrayList<>(); long tq = 0, tr = 0, tc = 0;
        // net of refunded quantities, pro-rata
        for (Object[] r : Db.query(c, "SELECT " + keyExpr + ", SUM(si.qty_milli - si.refunded_milli), " +
                "SUM(si.line_total_minor * (si.qty_milli - si.refunded_milli) / si.qty_milli), SUM(si.unit_cost_minor * (si.qty_milli - si.refunded_milli) / 1000) " +
                "FROM sale_items si JOIN sales s ON s.id = si.sale_id " + join + " WHERE s.status <> 'VOIDED' AND date(s.created_at) >= ? AND date(s.created_at) <= ? GROUP BY 1 ORDER BY 3 DESC",
                rs -> new Object[]{rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)}, from, to)) {
            rows.add(List.of((String) r[0], q((Long) r[1]), m((Long) r[2]), m((Long) r[2] - (Long) r[3])));
            tq += (Long) r[1]; tr += (Long) r[2]; tc += (Long) r[3];
        }
        return new Table(title, List.of(col, "Quantity", "Sales (incl. tax)", "Approx. margin"), rows, List.of("TOTAL", q(tq), m(tr), m(tr - tc)), List.of(false, true, true, true));
    }

    private Table byEmployee(Connection c, String title, String from, String to) throws SQLException {
        List<List<String>> rows = new ArrayList<>(); long tn = 0, tt = 0;
        for (Object[] r : Db.query(c, "SELECT u.full_name, COUNT(*), SUM(s.total_minor), SUM(s.discount_minor) FROM sales s JOIN users u ON u.id = s.cashier_id " +
                "WHERE s.status <> 'VOIDED' AND date(s.created_at) >= ? AND date(s.created_at) <= ? GROUP BY u.id ORDER BY 3 DESC",
                rs -> new Object[]{rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)}, from, to)) {
            rows.add(List.of((String) r[0], String.valueOf(r[1]), m((Long) r[2]), m((Long) r[3])));
            tn += (Long) r[1]; tt += (Long) r[2];
        }
        long voids = Db.scalarLong(c, "SELECT COUNT(*) FROM sales WHERE status = 'VOIDED' AND date(created_at) >= ? AND date(created_at) <= ?", from, to);
        return new Table(title + (voids > 0 ? " - " + voids + " voided sale(s) excluded" : ""), List.of("Employee", "Sales", "Total (incl. tax)", "Discounts given"), rows,
                List.of("TOTAL", String.valueOf(tn), m(tt), ""), List.of(false, true, true, true));
    }

    private Table pnl(Connection c, String title, String from, String to) throws SQLException {
        FinanceService.Summary f = FinanceService.compute(c, from, to);
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("Sales (incl. tax)", f.grossSales().toPlainString()));
        rows.add(List.of("Less: tax collected", f.taxCollected().negate().toPlainString()));
        rows.add(List.of("Less: refunds (ex tax)", f.grossSales().subtract(f.taxCollected()).subtract(f.revenue()).toPlainString()));
        rows.add(List.of("REVENUE", f.revenue().toPlainString()));
        rows.add(List.of("Less: cost of goods sold", f.cogs().negate().toPlainString()));
        rows.add(List.of("GROSS PROFIT", f.grossProfit().toPlainString()));
        for (Object[] e : Db.query(c, "SELECT category, SUM(amount_minor) FROM expenses WHERE incurred_on >= ? AND incurred_on <= ? GROUP BY category ORDER BY 2 DESC",
                rs -> new Object[]{rs.getString(1), rs.getLong(2)}, from, to))
            rows.add(List.of("  Expense: " + e[0], "-" + m((Long) e[1])));
        rows.add(List.of("Total expenses", f.expenses().negate().toPlainString()));
        rows.add(List.of("NET PROFIT", f.netProfit().toPlainString()));
        rows.add(List.of("", ""));
        rows.add(List.of("Cash received in period (not the same as revenue)", f.cashReceived().toPlainString()));
        rows.add(List.of("Customer balances outstanding (now)", f.receivables().toPlainString()));
        rows.add(List.of("Owed to suppliers (now)", f.payables().toPlainString()));
        return new Table(title, List.of("Item", "Amount"), rows, null, List.of(false, true));
    }

    private Table expenses(Connection c, String title, String from, String to) throws SQLException {
        List<List<String>> rows = new ArrayList<>(); long tot = 0;
        for (Object[] r : Db.query(c, "SELECT incurred_on, category, COALESCE(description,''), method, amount_minor FROM expenses WHERE incurred_on >= ? AND incurred_on <= ? ORDER BY incurred_on, id",
                rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5)}, from, to)) {
            rows.add(List.of((String) r[0], (String) r[1], (String) r[2], (String) r[3], m((Long) r[4]))); tot += (Long) r[4];
        }
        return new Table(title, List.of("Date", "Category", "Description", "Method", "Amount"), rows, List.of("TOTAL", "", "", "", m(tot)), List.of(false, false, false, false, true));
    }

    private Table stock(Connection c, Session s, String title, boolean lowOnly) throws SQLException {
        List<List<String>> rows = new ArrayList<>(); long tc = 0, tr = 0;
        for (Object[] r : Db.query(c, "SELECT p.sku, p.name, COALESCE(ca.name,''), COALESCE(bs.qty_milli,0), p.min_stock_milli, p.cost_minor, p.price_minor FROM products p " +
                "LEFT JOIN categories ca ON ca.id = p.category_id LEFT JOIN branch_stock bs ON bs.product_id = p.id AND bs.branch_id = ? WHERE p.archived = 0 " +
                (lowOnly ? "AND p.min_stock_milli > 0 AND COALESCE(bs.qty_milli,0) <= p.min_stock_milli " : "") + "ORDER BY p.name",
                rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7)}, s.branchId())) {
            long qty = (Long) r[3];
            long costVal = Math.max(0, qty) * (Long) r[5] / 1000, retailVal = Math.max(0, qty) * (Long) r[6] / 1000;
            rows.add(List.of((String) r[0], (String) r[1], (String) r[2], q(qty), q((Long) r[4]), m((Long) r[5]), m(costVal), m(retailVal)));
            tc += costVal; tr += retailVal;
        }
        return new Table(title, List.of("SKU", "Product", "Category", "On hand", "Min", "Unit cost", "Cost value", "Retail value"), rows,
                lowOnly ? null : List.of("TOTAL", "", "", "", "", "", m(tc), m(tr)), List.of(false, false, false, true, true, true, true, true));
    }

    private Table customers(Connection c, String title) throws SQLException {
        List<List<String>> rows = new ArrayList<>(); long tb = 0;
        for (Object[] r : Db.query(c, "SELECT cu.name, COALESCE(cu.phone,''), (SELECT COUNT(*) FROM sales WHERE customer_id = cu.id AND status <> 'VOIDED'), " +
                "(SELECT COALESCE(SUM(total_minor),0) FROM sales WHERE customer_id = cu.id AND status <> 'VOIDED'), cu.credit_limit_minor, cu.balance_minor FROM customers cu ORDER BY cu.balance_minor DESC, cu.name",
                rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6)})) {
            rows.add(List.of((String) r[0], (String) r[1], String.valueOf(r[2]), m((Long) r[3]), m((Long) r[4]), m((Long) r[5]))); tb += (Long) r[5];
        }
        return new Table(title, List.of("Customer", "Phone", "Sales", "Total purchases", "Credit limit", "Balance owed"), rows, List.of("TOTAL", "", "", "", "", m(tb)), List.of(false, false, true, true, true, true));
    }

    private Table suppliers(Connection c, String title) throws SQLException {
        List<List<String>> rows = new ArrayList<>(); long to = 0;
        for (Object[] r : Db.query(c, "SELECT su.name, COALESCE(su.phone,''), (SELECT COUNT(*) FROM purchases WHERE supplier_id = su.id AND status <> 'CANCELLED'), " +
                "(SELECT COALESCE(SUM(total_minor),0) FROM purchases WHERE supplier_id = su.id AND status IN ('RECEIVED','PARTIAL')), " +
                "(SELECT COALESCE(SUM(total_minor - paid_minor),0) FROM purchases WHERE supplier_id = su.id AND status IN ('RECEIVED','PARTIAL')) FROM suppliers su ORDER BY su.name",
                rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)})) {
            rows.add(List.of((String) r[0], (String) r[1], String.valueOf(r[2]), m((Long) r[3]), m((Long) r[4]))); to += (Long) r[4];
        }
        return new Table(title, List.of("Supplier", "Phone", "Orders", "Purchased", "We owe"), rows, List.of("TOTAL", "", "", "", m(to)), List.of(false, false, true, true, true));
    }

    private Table shifts(Connection c, String title, String from, String to) throws SQLException {
        List<List<String>> rows = new ArrayList<>(); long tv = 0;
        for (Object[] r : Db.query(c, "SELECT u.full_name, s.opened_at, COALESCE(s.closed_at,'open'), s.opening_minor, s.expected_minor, s.counted_minor, s.variance_minor " +
                "FROM cash_shifts s JOIN users u ON u.id = s.user_id WHERE date(s.opened_at) >= ? AND date(s.opened_at) <= ? ORDER BY s.id",
                rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getObject(5), rs.getObject(6), rs.getObject(7)}, from, to)) {
            long var = r[6] == null ? 0 : ((Number) r[6]).longValue(); tv += var;
            rows.add(List.of((String) r[0], (String) r[1], (String) r[2], m((Long) r[3]), r[4] == null ? "" : m(((Number) r[4]).longValue()), r[5] == null ? "" : m(((Number) r[5]).longValue()), r[6] == null ? "" : m(var)));
        }
        return new Table(title, List.of("Cashier", "Opened", "Closed", "Opening float", "Expected", "Counted", "Variance"), rows, List.of("NET VARIANCE", "", "", "", "", "", m(tv)), List.of(false, false, false, true, true, true, true));
    }
}
