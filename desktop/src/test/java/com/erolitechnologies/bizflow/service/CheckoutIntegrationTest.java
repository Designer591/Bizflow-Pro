package com.erolitechnologies.bizflow.service;

import com.erolitechnologies.bizflow.domain.sale.PaymentLine;
import com.erolitechnologies.bizflow.infra.AppException;
import com.erolitechnologies.bizflow.infra.Db;
import com.erolitechnologies.bizflow.security.Role;
import com.erolitechnologies.bizflow.testsupport.TestEnv;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CheckoutIntegrationTest {
    private static BigDecimal d(String s) { return new BigDecimal(s); }

    private CheckoutService.Request cart(long pid, String qty, String pay) {
        return new CheckoutService.Request(List.of(new CheckoutService.Line(pid, d(qty), null)), null, null,
                List.of(new PaymentLine("Cash", d(pay), null)));
    }

    private static long scalar(TestEnv e, String sql) {
        return e.db.read(c -> Db.scalarLong(c, sql));
    }

    private BigDecimal stock(TestEnv e, long pid) {
        return e.db.read(c -> com.erolitechnologies.bizflow.service.StockService.onHand(c, e.admin.branchId(), pid));
    }

    @Test
    void checkoutRecordsEverythingAndDeductsStock() {
        var e = new TestEnv();
        long p = e.product("Bread", "1.00", "2.50", "10");
        var r = e.checkout.checkout(e.admin, cart(p, "3", "10.00"));
        assertEquals(d("7.50"), r.totals().total());
        assertEquals(d("2.50"), r.change());
        assertEquals(d("7"), stock(e, p).stripTrailingZeros().setScale(0));
        assertEquals(1L, scalar(e, "SELECT COUNT(*) FROM sales"));
        assertEquals(1L, scalar(e, "SELECT COUNT(*) FROM sale_items"));
        assertEquals(1L, scalar(e, "SELECT COUNT(*) FROM payments"));
        assertEquals(1L, scalar(e, "SELECT COUNT(*) FROM stock_movements WHERE type='SALE'"));
        assertTrue(r.receiptNo().startsWith("RCP-"));
    }

    @Test
    void insufficientStockRollsBackEverything() {
        var e = new TestEnv();
        long a = e.product("Plenty", "1", "2", "100");
        long b = e.product("Scarce", "1", "2", "1");
        var req = new CheckoutService.Request(List.of(
                new CheckoutService.Line(a, d("5"), null), new CheckoutService.Line(b, d("2"), null)), null, null,
                List.of(new PaymentLine("Cash", d("20"), null)));
        assertThrows(InsufficientStockException.class, () -> e.checkout.checkout(e.admin, req));
        assertEquals(0L, scalar(e, "SELECT COUNT(*) FROM sales"));
        assertEquals(0L, scalar(e, "SELECT COUNT(*) FROM payments"));
        assertEquals(d("100"), stock(e, a).stripTrailingZeros().setScale(0), "first line's stock deduction must be rolled back");
    }

    @Test
    void sellingExactlyTheLastUnitWorksThenNextSaleFails() {
        var e = new TestEnv();
        long p = e.product("Last", "1", "2", "1");
        e.checkout.checkout(e.admin, cart(p, "1", "2"));
        assertThrows(InsufficientStockException.class, () -> e.checkout.checkout(e.admin, cart(p, "1", "2")));
    }

    @Test
    void priceComesFromDatabaseNotFromCaller() {
        var e = new TestEnv();
        long p = e.product("Item", "1", "10.00", "5");
        var r = e.checkout.checkout(e.admin, cart(p, "1", "10.00"));
        assertEquals(d("10.00"), r.totals().total());
    }

    @Test
    void taxIsAppliedFromSettings() {
        var e = new TestEnv();
        e.settings.update(e.admin, Map.of("tax_enabled", "true", "tax_rate", "15"));
        long p = e.product("Taxed", "1", "100.00", "5");
        var r = e.checkout.checkout(e.admin, cart(p, "1", "115.00"));
        assertEquals(d("15.00"), r.totals().tax());
        assertEquals(d("115.00"), r.totals().total());
    }

    @Test
    void splitPaymentsAreRecorded() {
        var e = new TestEnv();
        long p = e.product("Item", "1", "20.00", "5");
        var req = new CheckoutService.Request(List.of(new CheckoutService.Line(p, d("1"), null)), null, null,
                List.of(new PaymentLine("EcoCash", d("12.00"), "EC123"), new PaymentLine("Cash", d("10.00"), null)));
        var r = e.checkout.checkout(e.admin, req);
        assertEquals(d("2.00"), r.change());
        assertEquals(2L, scalar(e, "SELECT COUNT(*) FROM payments"));
        assertEquals(8_00L, scalar(e, "SELECT amount_minor FROM payments WHERE method='Cash'"));
    }

    @Test
    void underpaymentIsRejectedAndNothingSaved() {
        var e = new TestEnv();
        long p = e.product("Item", "1", "20.00", "5");
        assertThrows(AppException.class, () -> e.checkout.checkout(e.admin, cart(p, "1", "5.00")));
        assertEquals(0L, scalar(e, "SELECT COUNT(*) FROM sales"));
        assertEquals(d("5"), stock(e, p).stripTrailingZeros().setScale(0));
    }

    @Test
    void archivedProductsCannotBeSold() {
        var e = new TestEnv();
        long p = e.product("Old", "1", "2", "5");
        e.products.setArchived(e.admin, p, true);
        assertThrows(AppException.class, () -> e.checkout.checkout(e.admin, cart(p, "1", "2")));
    }

    @Test
    void shiftRequiredBeforeSelling() {
        var e = new TestEnv();
        long p = e.product("Item", "1", "2", "5");
        var cashier = e.userWithRole("cash1", Role.CASHIER);
        var ex = assertThrows(AppException.class, () -> e.checkout.checkout(cashier, cart(p, "1", "2")));
        assertTrue(ex.getMessage().contains("shift"));
        e.shifts.open(cashier, d("20"));
        e.checkout.checkout(cashier, cart(p, "1", "2"));
    }

    @Test
    void cashierDiscountLimitEnforced() {
        var e = new TestEnv();
        long p = e.product("Item", "1", "100.00", "5");
        var cashier = e.userWithRole("cash1", Role.CASHIER);
        e.shifts.open(cashier, d("0"));
        var small = new CheckoutService.Request(List.of(new CheckoutService.Line(p, d("1"), d("10.00"))), null, null, List.of(new PaymentLine("Cash", d("90"), null)));
        e.checkout.checkout(cashier, small); // exactly 10%
        var big = new CheckoutService.Request(List.of(new CheckoutService.Line(p, d("1"), d("30.00"))), null, null, List.of(new PaymentLine("Cash", d("70"), null)));
        assertThrows(AppException.class, () -> e.checkout.checkout(cashier, big));
        e.checkout.checkout(e.admin, big); // admin may approve large discounts
    }

    @Test
    void creditSaleUpdatesBalanceAndRespectsLimit() {
        var e = new TestEnv();
        long p = e.product("Item", "1", "30.00", "10");
        long cust = e.db.tx(c -> Db.insert(c, "INSERT INTO customers(name, credit_enabled, credit_limit_minor) VALUES ('Cr', 1, 5000)"));
        var ok = new CheckoutService.Request(List.of(new CheckoutService.Line(p, d("1"), null)), null, cust,
                List.of(new PaymentLine("Customer Credit", d("30.00"), null)));
        e.checkout.checkout(e.admin, ok);
        assertEquals(3000L, scalar(e, "SELECT balance_minor FROM customers WHERE id=" + cust));
        assertThrows(AppException.class, () -> e.checkout.checkout(e.admin, ok)); // 60 > 50 limit
        assertEquals(3000L, scalar(e, "SELECT balance_minor FROM customers WHERE id=" + cust));
    }

    @Test
    void permissionsAreEnforced() {
        var e = new TestEnv();
        long p = e.product("Item", "1", "2", "5");
        var inv = e.userWithRole("inv1", Role.INVENTORY_OFFICER);
        assertThrows(AppException.class, () -> e.checkout.checkout(inv, cart(p, "1", "2")));
        var cashier = e.userWithRole("cash2", Role.CASHIER);
        assertThrows(AppException.class, () -> e.users.list(cashier));
        assertThrows(AppException.class, () -> e.settings.update(cashier, Map.of("tax_rate", "5")));
        assertThrows(AppException.class, () -> e.products.create(cashier, new com.erolitechnologies.bizflow.service.ProductService.ProductInput(null, null, null, null, "X", null, null, null, d("1"), d("2"), false, null, null)));
    }

    @Test
    void concurrentSalesNeverOversell() throws Exception {
        var e = new TestEnv();
        long p = e.product("Hot item", "1", "2", "10");
        int threads = 8, each = 3; // 24 attempts for 10 units
        var ok = new java.util.concurrent.atomic.AtomicInteger();
        var failed = new java.util.concurrent.atomic.AtomicInteger();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for (int t = 0; t < threads; t++) futures.add(pool.submit(() -> {
            for (int i = 0; i < each; i++) {
                try { e.checkout.checkout(e.admin, cart(p, "1", "2")); ok.incrementAndGet(); }
                catch (InsufficientStockException ex) { failed.incrementAndGet(); }
            }
        }));
        for (var f : futures) f.get();
        pool.shutdown();
        assertEquals(10, ok.get());
        assertEquals(14, failed.get());
        assertEquals(d("0"), stock(e, p).stripTrailingZeros().setScale(0));
        assertEquals(10L, scalar(e, "SELECT COUNT(*) FROM sales"));
    }

    // ---- refunds & voids ----

    @Test
    void partialThenFullRefundRestocksAndKeepsHistory() {
        var e = new TestEnv();
        long p = e.product("Item", "2.00", "10.00", "10");
        var r = e.checkout.checkout(e.admin, cart(p, "4", "40.00"));
        long itemId = e.db.read(c -> Db.scalarLong(c, "SELECT id FROM sale_items WHERE sale_id=" + r.saleId()));
        var amt1 = e.refunds.refund(e.admin, r.saleId(), Map.of(itemId, d("1")), "Cash", true, "Damaged");
        assertEquals(d("10.00"), amt1);
        assertEquals("PARTIALLY_REFUNDED", e.db.read(c -> Db.queryOne(c, "SELECT status FROM sales WHERE id=" + r.saleId(), rs -> rs.getString(1))));
        assertThrows(AppException.class, () -> e.refunds.refund(e.admin, r.saleId(), Map.of(itemId, d("4")), "Cash", true, "Too many"));
        var amt2 = e.refunds.refund(e.admin, r.saleId(), Map.of(itemId, d("3")), "Cash", true, "Rest");
        assertEquals(d("30.00"), amt2);
        assertEquals("REFUNDED", e.db.read(c -> Db.queryOne(c, "SELECT status FROM sales WHERE id=" + r.saleId(), rs -> rs.getString(1))));
        assertEquals(d("10"), stock(e, p).stripTrailingZeros().setScale(0));
        assertEquals(1L, scalar(e, "SELECT COUNT(*) FROM sales"), "sale row must remain");
        assertEquals(2L, scalar(e, "SELECT COUNT(*) FROM refunds"));
    }

    @Test
    void voidRestoresStockReversesPaymentsAndCannotRepeat() {
        var e = new TestEnv();
        long p = e.product("Item", "1", "5.00", "10");
        var r = e.checkout.checkout(e.admin, cart(p, "2", "10.00"));
        e.refunds.voidSale(e.admin, r.saleId(), "Wrong customer");
        assertEquals(d("10"), stock(e, p).stripTrailingZeros().setScale(0));
        assertEquals("VOIDED", e.db.read(c -> Db.queryOne(c, "SELECT status FROM sales WHERE id=" + r.saleId(), rs -> rs.getString(1))));
        assertEquals(1000L, scalar(e, "SELECT amount_minor FROM payments WHERE direction='OUT'"));
        assertThrows(AppException.class, () -> e.refunds.voidSale(e.admin, r.saleId(), "again"));
        assertEquals(1L, scalar(e, "SELECT COUNT(*) FROM audit_logs WHERE action='SALE_VOIDED'"));
    }

    @Test
    void cashierCannotRefundOrVoid() {
        var e = new TestEnv();
        long p = e.product("Item", "1", "5.00", "10");
        var r = e.checkout.checkout(e.admin, cart(p, "1", "5.00"));
        var cashier = e.userWithRole("cash1", Role.CASHIER);
        assertThrows(AppException.class, () -> e.refunds.voidSale(cashier, r.saleId(), "x"));
        assertThrows(AppException.class, () -> e.refunds.refund(cashier, r.saleId(), Map.of(1L, d("1")), "Cash", true, "x"));
    }

    @Test
    void shiftReconciliationUsesCashOnly() {
        var e = new TestEnv(); // admin shift opened with 50.00 float
        long p = e.product("Item", "1", "20.00", "10");
        e.checkout.checkout(e.admin, new CheckoutService.Request(List.of(new CheckoutService.Line(p, d("1"), null)), null, null,
                List.of(new PaymentLine("Cash", d("20.00"), null))));
        e.checkout.checkout(e.admin, new CheckoutService.Request(List.of(new CheckoutService.Line(p, d("1"), null)), null, null,
                List.of(new PaymentLine("Card", d("20.00"), null))));
        assertEquals(d("70.00"), e.shifts.expectedNow(e.admin));
        var closed = e.shifts.close(e.admin, d("69.00"), "short by one");
        assertEquals(d("-1.00"), closed.variance());
        assertEquals("CLOSED", closed.status());
        assertThrows(AppException.class, () -> e.shifts.close(e.admin, d("0"), null));
    }
}
