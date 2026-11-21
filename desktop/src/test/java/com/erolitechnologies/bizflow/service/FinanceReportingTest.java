package com.erolitechnologies.bizflow.service;

import com.erolitechnologies.bizflow.domain.sale.PaymentLine;
import com.erolitechnologies.bizflow.infra.AppException;
import com.erolitechnologies.bizflow.report.PdfWriter;
import com.erolitechnologies.bizflow.report.Table;
import com.erolitechnologies.bizflow.security.Role;
import com.erolitechnologies.bizflow.testsupport.TestEnv;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FinanceReportingTest {
    private static BigDecimal d(String s) { return new BigDecimal(s); }
    private final String today = LocalDate.now().toString();

    private void sell(TestEnv e, long p, String qty, String pay, String method, Long cust) {
        e.checkout.checkout(e.admin, new CheckoutService.Request(List.of(new CheckoutService.Line(p, d(qty), null)), null, cust, List.of(new PaymentLine(method, d(pay), null))));
    }

    @Test
    void profitDefinitionsAreDistinctAndCorrect() {
        var e = new TestEnv();
        var fin = new FinanceService(e.db);
        var exp = new ExpenseService(e.db);
        e.settings.update(e.admin, Map.of("tax_enabled", "true", "tax_rate", "10"));
        long p = e.product("Item", "6.00", "10.00", "100");
        sell(e, p, "10", "110.00", "Cash", null);          // revenue 100 (+10 tax), COGS 60
        exp.add(e.admin, "Rent", "Shop", d("15.00"), "Cash", LocalDate.now());
        var s = fin.summary(e.admin, today, today);
        assertEquals(d("110.00"), s.grossSales());
        assertEquals(d("10.00"), s.taxCollected());
        assertEquals(d("100.00"), s.revenue());
        assertEquals(d("60.00"), s.cogs());
        assertEquals(d("40.00"), s.grossProfit());
        assertEquals(d("15.00"), s.expenses());
        assertEquals(d("25.00"), s.netProfit());
        assertEquals(d("110.00"), s.cashReceived());
        assertEquals(1, s.transactions());
    }

    @Test
    void creditSalesAreRevenueButNotCashUntilPaid() {
        var e = new TestEnv();
        var fin = new FinanceService(e.db);
        var customers = new CustomerService(e.db);
        long cust = customers.create(e.admin, "Jane", null, null, null, true, d("500"));
        long p = e.product("Item", "5.00", "20.00", "10");
        sell(e, p, "2", "40.00", "Customer Credit", cust);
        var s = fin.summary(e.admin, today, today);
        assertEquals(d("40.00"), s.revenue());
        assertEquals(d("0.00"), s.cashReceived());
        assertEquals(d("40.00"), s.receivables());
        customers.receivePayment(e.admin, cust, d("15.00"), "Cash", null);
        assertEquals(d("15.00"), fin.summary(e.admin, today, today).cashReceived());
    }

    @Test
    void refundsAndVoidsAdjustProfitCorrectly() {
        var e = new TestEnv();
        var fin = new FinanceService(e.db);
        long p = e.product("Item", "4.00", "10.00", "20");
        sell(e, p, "4", "40.00", "Cash", null);   // revenue 40 cogs 16
        var voided = e.checkout.checkout(e.admin, new CheckoutService.Request(List.of(new CheckoutService.Line(p, d("2"), null)), null, null, List.of(new PaymentLine("Cash", d("20.00"), null))));
        e.refunds.voidSale(e.admin, voided.saleId(), "mistake");
        var s1 = fin.summary(e.admin, today, today);
        assertEquals(d("40.00"), s1.revenue());
        assertEquals(1, s1.transactions(), "voided sale is excluded");
        assertEquals(d("40.00"), s1.cashReceived(), "voided cash was paid back");
        long sale = e.db.read(c -> com.erolitechnologies.bizflow.infra.Db.scalarLong(c, "SELECT id FROM sales WHERE status='COMPLETED'"));
        long item = e.db.read(c -> com.erolitechnologies.bizflow.infra.Db.scalarLong(c, "SELECT id FROM sale_items WHERE sale_id=" + sale));
        e.refunds.refund(e.admin, sale, Map.of(item, d("1")), "Cash", true, "unhappy");
        var s2 = fin.summary(e.admin, today, today);
        assertEquals(d("30.00"), s2.revenue());
        assertEquals(d("12.00"), s2.cogs());
        assertEquals(d("18.00"), s2.grossProfit());
        assertEquals(d("30.00"), s2.cashReceived());
    }

    @Test
    void financialFiguresAreHiddenFromUsersWithoutPermission() {
        var e = new TestEnv();
        var cashier = e.userWithRole("cash", Role.CASHIER);
        assertThrows(AppException.class, () -> new FinanceService(e.db).summary(cashier, today, today));
        assertThrows(AppException.class, () -> new DashboardService(e.db).load(cashier, DashboardService.Range.TODAY, null, null));
        var inv = e.userWithRole("inv", Role.INVENTORY_OFFICER);
        var data = new DashboardService(e.db).load(inv, DashboardService.Range.TODAY, null, null);
        assertNull(data.netProfit());
        assertNull(data.expenses());
        assertThrows(AppException.class, () -> new ReportService(e.db).run(inv, ReportService.Type.PROFIT_AND_LOSS, today, today));
    }

    @Test
    void dashboardComesFromRealData() {
        var e = new TestEnv(true);
        long p = e.products.search(e.admin, "Maize", null, false, false).get(0).id();
        sell(e, p, "2", "19.00", "Cash", null);
        sell(e, p, "1", "9.50", "EcoCash", null);
        var dash = new DashboardService(e.db).load(e.admin, DashboardService.Range.TODAY, null, null);
        assertEquals(2, dash.transactions());
        assertEquals(d("28.50"), dash.revenue());
        assertEquals(13, dash.products());
        assertTrue(dash.lowStock() >= 1);
        assertEquals("Maize Meal 10kg", dash.top().get(0).name());
        assertEquals(d("3"), dash.top().get(0).qty().stripTrailingZeros().setScale(0));
        assertEquals(2, dash.methods().size());
        assertEquals(1, dash.trend().size());
        assertEquals(2, dash.recent().size());
        assertThrows(AppException.class, () -> new DashboardService(e.db).load(e.admin, DashboardService.Range.CUSTOM, LocalDate.now(), LocalDate.now().minusDays(1)));
        var yesterday = new DashboardService(e.db).load(e.admin, DashboardService.Range.YESTERDAY, null, null);
        assertEquals(0, yesterday.transactions());
    }

    @Test
    void expensesValidateAndDeleteIsAudited() {
        var e = new TestEnv();
        var exp = new ExpenseService(e.db);
        assertThrows(AppException.class, () -> exp.add(e.admin, "Rent", null, d("0"), "Cash", LocalDate.now()));
        assertThrows(AppException.class, () -> exp.add(e.admin, "Rent", null, d("5"), "Cash", LocalDate.now().plusDays(30)));
        long id = exp.add(e.admin, "Rent", null, d("5"), "Cash", LocalDate.now());
        assertThrows(AppException.class, () -> exp.delete(e.admin, id, ""));
        exp.delete(e.admin, id, "entered twice");
        assertEquals(0, exp.list(e.admin, null, null).size());
    }

    @Test
    void everyReportRunsAndTotalsReconcile() {
        var e = new TestEnv(true);
        var reports = new ReportService(e.db);
        long p = e.products.search(e.admin, "Sugar", null, false, false).get(0).id();
        sell(e, p, "3", "9.90", "Cash", null);
        new ExpenseService(e.db).add(e.admin, "Utilities", "Power", d("4.00"), "Cash", LocalDate.now());
        for (var type : ReportService.Type.values()) {
            Table t = reports.run(e.admin, type, today, today);
            assertFalse(t.headers().isEmpty(), type.name());
            for (var row : t.rows()) assertEquals(t.headers().size(), row.size(), type + " row width");
            if (t.totals() != null) assertEquals(t.headers().size(), t.totals().size(), type + " totals width");
        }
        Table daily = reports.run(e.admin, ReportService.Type.DAILY_SALES, today, today);
        assertEquals("9.90", daily.totals().get(5));
        Table stock = reports.run(e.admin, ReportService.Type.STOCK, today, today);
        assertEquals(13, stock.rows().size());
        Table low = reports.run(e.admin, ReportService.Type.LOW_STOCK, today, today);
        assertTrue(low.rows().size() >= 1 && low.rows().size() < 13);
        Table pnl = reports.run(e.admin, ReportService.Type.PROFIT_AND_LOSS, today, today);
        assertTrue(pnl.rows().stream().anyMatch(r -> r.get(0).equals("NET PROFIT")));
        assertThrows(AppException.class, () -> reports.run(e.admin, ReportService.Type.DAILY_SALES, today, "2000-01-01"));
    }

    @Test
    void receiptsFitEachPaperWidthAndReprintIsMarked() {
        var e = new TestEnv();
        e.settings.update(e.admin, Map.of("tax_enabled", "true", "tax_rate", "15"));
        long p = e.product("A very long product name that must be trimmed to fit narrow thermal paper", "1", "10.00", "10");
        var r = e.checkout.checkout(e.admin, new CheckoutService.Request(List.of(new CheckoutService.Line(p, d("2"), d("1.00"))), null, null,
                List.of(new PaymentLine("Cash", d("30.00"), null))));
        var receipts = new ReceiptService(e.db);
        for (String paper : List.of("58mm", "80mm", "A4")) {
            e.settings.update(e.admin, Map.of("receipt_paper", paper));
            int w = ReceiptService.widthFor(paper);
            var lines = receipts.render(e.admin, r.saleId(), false);
            for (String l : lines) assertTrue(l.length() <= w, paper + " line too long: " + l);
        }
        var lines = receipts.render(e.admin, r.saleId(), true);
        String all = String.join("\n", lines);
        assertTrue(all.contains("TEST SHOP"));
        assertTrue(all.contains(r.receiptNo()));
        assertTrue(all.contains("REPRINT"));
        assertTrue(all.contains("Cashier:"));
        assertTrue(all.contains("Change due"));
        assertTrue(all.contains("Thanks!"));
        assertTrue(all.contains("VAT"));
        var cashier2 = e.userWithRole("cash2", Role.CASHIER);
        assertThrows(AppException.class, () -> receipts.render(cashier2, r.saleId(), true), "cashiers can only reprint their own receipts");
    }

    @Test
    void pdfIsWellFormedPaginatesAndContainsBusinessHeader() throws Exception {
        List<List<String>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 120; i++) rows.add(List.of("Row " + i, "(paren) \\ back", String.valueOf(i), "1.00"));
        Table t = new Table("Big report", List.of("Name", "Notes", "Qty", "Amount"), rows, List.of("TOTAL", "", "", "120.00"), List.of(false, false, true, true));
        byte[] pdf = PdfWriter.render(new PdfWriter.Header("Test Shop", "1 Main St | 077", "Admin", "2026-11-21 10:00"), t);
        String s = new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(s.startsWith("%PDF-1.4"));
        assertTrue(s.contains("%%EOF"));
        assertTrue(s.contains("Test Shop"));
        assertTrue(s.contains("Page 1 of "));
        assertTrue(s.contains("/Count 4") || s.contains("/Count 5") || s.contains("/Count 3"), "120 rows must span several pages");
        java.nio.file.Path f = java.nio.file.Files.createTempFile("bizflow", ".pdf");
        java.nio.file.Files.write(f, pdf);
        System.setProperty("bizflow.test.pdf", f.toString());
    }
}
