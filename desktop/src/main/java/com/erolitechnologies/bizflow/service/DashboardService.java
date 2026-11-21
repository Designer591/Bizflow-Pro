package com.erolitechnologies.bizflow.service;

import com.erolitechnologies.bizflow.domain.Money;
import com.erolitechnologies.bizflow.infra.Db;
import com.erolitechnologies.bizflow.security.Permission;
import com.erolitechnologies.bizflow.security.Session;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

public final class DashboardService {
    public enum Range {
        TODAY, YESTERDAY, THIS_WEEK, THIS_MONTH, CUSTOM;

        public LocalDate[] resolve(LocalDate today, LocalDate customFrom, LocalDate customTo) {
            return switch (this) {
                case TODAY -> new LocalDate[]{today, today};
                case YESTERDAY -> new LocalDate[]{today.minusDays(1), today.minusDays(1)};
                case THIS_WEEK -> new LocalDate[]{today.with(DayOfWeek.MONDAY), today};
                case THIS_MONTH -> new LocalDate[]{today.withDayOfMonth(1), today};
                case CUSTOM -> new LocalDate[]{customFrom, customTo};
            };
        }
    }

    public record Recent(String receiptNo, String time, String cashier, BigDecimal total, String status) {}
    public record TopProduct(String name, BigDecimal qty, BigDecimal revenue) {}
    public record DayPoint(String day, BigDecimal revenue, int transactions) {}
    public record MethodShare(String method, BigDecimal amount) {}

    public record Data(String from, String to, BigDecimal revenue, BigDecimal totalSales, int transactions, BigDecimal grossProfit,
                       BigDecimal expenses, BigDecimal netProfit, int products, int lowStock, List<Recent> recent,
                       List<TopProduct> top, List<DayPoint> trend, List<MethodShare> methods) {}

    private final Db db;

    public DashboardService(Db db) { this.db = db; }

    /** Financial figures (profit, expenses) are null unless the user has FINANCE_VIEW. */
    public Data load(Session s, Range range, LocalDate customFrom, LocalDate customTo) {
        s.require(Permission.DASHBOARD_VIEW);
        LocalDate[] r = range.resolve(LocalDate.now(), customFrom, customTo);
        if (r[0] == null || r[1] == null || r[0].isAfter(r[1])) throw new com.erolitechnologies.bizflow.infra.AppException("Choose a valid date range.");
        String from = r[0].toString(), to = r[1].toString();
        boolean fin = s.has(Permission.FINANCE_VIEW);
        return db.read(c -> {
            FinanceService.Summary sm = FinanceService.compute(c, from, to);
            int products = (int) Db.scalarLong(c, "SELECT COUNT(*) FROM products WHERE archived = 0");
            int low = (int) Db.scalarLong(c, "SELECT COUNT(*) FROM products p LEFT JOIN branch_stock bs ON bs.product_id = p.id AND bs.branch_id = ? " +
                    "WHERE p.archived = 0 AND p.min_stock_milli > 0 AND COALESCE(bs.qty_milli,0) <= p.min_stock_milli", s.branchId());
            List<Recent> recent = Db.query(c, "SELECT s.receipt_no, s.created_at, u.full_name, s.total_minor, s.status FROM sales s JOIN users u ON u.id = s.cashier_id " +
                    "WHERE date(s.created_at) >= ? AND date(s.created_at) <= ? ORDER BY s.id DESC LIMIT 10",
                    rs -> new Recent(rs.getString(1), rs.getString(2), rs.getString(3), Money.fromMinor(rs.getLong(4)), rs.getString(5)), from, to);
            List<TopProduct> top = Db.query(c, "SELECT si.description, SUM(si.qty_milli - si.refunded_milli), SUM(si.line_total_minor * (si.qty_milli - si.refunded_milli) / si.qty_milli) " +
                    "FROM sale_items si JOIN sales s ON s.id = si.sale_id WHERE s.status <> 'VOIDED' AND date(s.created_at) >= ? AND date(s.created_at) <= ? " +
                    "GROUP BY si.product_id, si.description ORDER BY 2 DESC LIMIT 5",
                    rs -> new TopProduct(rs.getString(1), Money.qtyFromMilli(rs.getLong(2)), Money.fromMinor(rs.getLong(3))), from, to);
            List<DayPoint> trend = Db.query(c, "SELECT date(created_at), COALESCE(SUM(total_minor - tax_minor),0), COUNT(*) FROM sales WHERE status <> 'VOIDED' " +
                    "AND date(created_at) >= ? AND date(created_at) <= ? GROUP BY date(created_at) ORDER BY 1",
                    rs -> new DayPoint(rs.getString(1), Money.fromMinor(rs.getLong(2)), rs.getInt(3)), from, to);
            List<MethodShare> methods = Db.query(c, "SELECT p.method, SUM(CASE WHEN p.direction='IN' THEN p.amount_minor ELSE -p.amount_minor END) FROM payments p " +
                    "WHERE p.purchase_id IS NULL AND date(p.created_at) >= ? AND date(p.created_at) <= ? GROUP BY p.method HAVING 1=1 ORDER BY 2 DESC",
                    rs -> new MethodShare(rs.getString(1), Money.fromMinor(rs.getLong(2))), from, to);
            return new Data(from, to, sm.revenue(), sm.grossSales(), sm.transactions(), fin ? sm.grossProfit() : null,
                    fin ? sm.expenses() : null, fin ? sm.netProfit() : null, products, low, recent, top, trend, methods);
        });
    }
}
