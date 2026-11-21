package com.erolitechnologies.bizflow.service;

import com.erolitechnologies.bizflow.infra.AppException;
import com.erolitechnologies.bizflow.infra.Db;
import com.erolitechnologies.bizflow.security.Role;
import com.erolitechnologies.bizflow.testsupport.TestEnv;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InventoryPurchasingTest {
    private static BigDecimal d(String s) { return new BigDecimal(s); }
    private static long scalar(TestEnv e, String sql) { return e.db.read(c -> Db.scalarLong(c, sql)); }

    @Test
    void productCreateGeneratesSkuAndRejectsDuplicates() {
        var e = new TestEnv();
        long id = e.product("Soap", "1", "2", "10");
        var p = e.products.get(e.admin, id);
        assertTrue(p.sku().startsWith("PRD-"));
        assertEquals(d("10.000"), p.stock());
        e.products.create(e.admin, new ProductService.ProductInput(null, null, "SKU-1", "123456", "A", null, null, null, d("1"), d("2"), false, null, null));
        assertThrows(AppException.class, () -> e.products.create(e.admin, new ProductService.ProductInput(null, null, "SKU-2", "123456", "B", null, null, null, d("1"), d("2"), false, null, null)));
        assertThrows(AppException.class, () -> e.products.create(e.admin, new ProductService.ProductInput(null, null, "SKU-1", null, "C", null, null, null, d("1"), d("2"), false, null, null)));
    }

    @Test
    void productValidation() {
        var e = new TestEnv();
        assertThrows(AppException.class, () -> e.products.create(e.admin, new ProductService.ProductInput(null, null, null, null, " ", null, null, null, d("1"), d("2"), false, null, null)));
        assertThrows(AppException.class, () -> e.products.create(e.admin, new ProductService.ProductInput(null, null, null, null, "X", null, null, null, d("-1"), d("2"), false, null, null)));
        assertThrows(AppException.class, () -> e.products.create(e.admin, new ProductService.ProductInput(null, null, null, null, "X", null, null, null, d("1"), d("-2"), false, null, null)));
    }

    @Test
    void barcodeLookupSearchArchiveAndLowStock() {
        var e = new TestEnv();
        long id = e.products.create(e.admin, new ProductService.ProductInput(null, null, null, "5901234123457", "Milk 1L", null, null, null, d("1"), d("2"), false, d("5"), d("3")));
        assertEquals("Milk 1L", e.products.findByCode(e.admin, "5901234123457").name());
        assertEquals(1, e.products.search(e.admin, "milk", null, false, false).size());
        assertEquals(1, e.products.search(e.admin, null, null, false, true).size(), "3 on hand <= min 5 is low stock");
        e.products.setArchived(e.admin, id, true);
        assertEquals(0, e.products.search(e.admin, "milk", null, false, false).size());
        assertEquals(1, e.products.search(e.admin, "milk", null, true, false).size());
        assertNull(e.products.findByCode(e.admin, "5901234123457"));
    }

    @Test
    void stockAdjustmentNeedsReasonCannotGoNegativeAndIsLogged() {
        var e = new TestEnv();
        long id = e.product("Item", "1", "2", "5");
        assertThrows(AppException.class, () -> e.stock.adjust(e.admin, id, d("1"), " "));
        assertThrows(InsufficientStockException.class, () -> e.stock.adjust(e.admin, id, d("-6"), "count"));
        assertEquals(d("3.000"), e.stock.adjust(e.admin, id, d("-2"), "damaged"));
        var mv = e.stock.movements(e.admin, id, 10);
        assertEquals("ADJUSTMENT", mv.get(0).type());
        assertEquals(d("3.000"), mv.get(0).balance());
        assertEquals("OPENING", mv.get(1).type());
    }

    @Test
    void negativeStockOnlyWhenAdministratorEnablesIt() {
        var e = new TestEnv();
        long id = e.product("Item", "1", "2", "1");
        e.settings.update(e.admin, Map.of("allow_negative_stock", "true"));
        assertEquals(d("-1.000"), e.stock.adjust(e.admin, id, d("-2"), "oversold earlier"));
        var inv = e.userWithRole("inv", Role.INVENTORY_OFFICER);
        assertThrows(InsufficientStockException.class, () -> e.stock.adjust(inv, id, d("-5"), "non-admin cannot go negative"));
    }

    @Test
    void stockTransferBetweenBranchesIsAtomic() {
        var e = new TestEnv();
        var branches = new BranchService(e.db);
        long b2 = branches.create(e.admin, "Second", "BR2", null, null);
        long id = e.product("Item", "1", "2", "10");
        long main = branches.mainBranchId();
        e.stock.transfer(e.admin, main, b2, id, d("4"));
        assertEquals(d("6.000"), com.erolitechnologies.bizflow.domain.Money.qtyFromMilli(scalar(e, "SELECT qty_milli FROM branch_stock WHERE branch_id=" + main + " AND product_id=" + id)));
        assertEquals(4000L, scalar(e, "SELECT qty_milli FROM branch_stock WHERE branch_id=" + b2 + " AND product_id=" + id));
        assertThrows(InsufficientStockException.class, () -> e.stock.transfer(e.admin, main, b2, id, d("100")));
        assertEquals(1L, scalar(e, "SELECT COUNT(*) FROM stock_transfers"), "failed transfer leaves no record");
        assertThrows(AppException.class, () -> e.stock.transfer(e.admin, main, main, id, d("1")));
    }

    @Test
    void valuationUsesCostAndRetail() {
        var e = new TestEnv();
        e.product("A", "2.00", "5.00", "10");
        e.product("B", "1.50", "3.00", "4");
        var v = e.products.valuation(e.admin, e.admin.branchId());
        assertEquals(d("26.00"), v.costValue());
        assertEquals(d("62.00"), v.retailValue());
    }

    @Test
    void categoryWithProductsCannotBeDeleted() {
        var e = new TestEnv();
        long cat = e.products.createCategory(e.admin, "Drinks");
        e.products.create(e.admin, new ProductService.ProductInput(cat, null, null, null, "Cola", null, null, null, d("1"), d("2"), false, null, null));
        assertThrows(AppException.class, () -> e.products.deleteCategory(e.admin, cat));
    }

    @Test
    void purchaseOrderPartialAndFullReceiptUpdatesStockCostAndStatus() {
        var e = new TestEnv();
        var suppliers = new SupplierService(e.db);
        var purchases = new PurchaseService(e.db);
        long sup = suppliers.create(e.admin, "Acme Wholesale", "Joe", "+263 77 111 2222", null, null);
        long p = e.product("Rice", "1.00", "2.00", "0");
        long po = purchases.createOrder(e.admin, sup, List.of(new PurchaseService.OrderLine(p, d("100"), d("1.20"))), null);
        assertEquals(0L, scalar(e, "SELECT COALESCE(qty_milli,0) FROM branch_stock WHERE product_id=" + p + " UNION SELECT 0 LIMIT 1"), "ordering does not change stock");
        long item = purchases.items(e.admin, po).get(0).id();
        purchases.receive(e.admin, po, Map.of(item, d("60")), "INV-9");
        assertEquals("PARTIAL", purchases.list(e.admin, null).get(0).status());
        assertEquals(60000L, scalar(e, "SELECT qty_milli FROM branch_stock WHERE product_id=" + p));
        assertThrows(AppException.class, () -> purchases.receive(e.admin, po, Map.of(item, d("41")), null));
        purchases.receive(e.admin, po, Map.of(item, d("40")), null);
        assertEquals("RECEIVED", purchases.list(e.admin, null).get(0).status());
        assertEquals(120L, scalar(e, "SELECT cost_minor FROM products WHERE id=" + p), "product cost follows latest purchase cost");
        assertThrows(AppException.class, () -> purchases.receive(e.admin, po, Map.of(item, d("1")), null));
        assertEquals(d("120.00"), suppliers.search(e.admin, null, false).get(0).balance());
        purchases.paySupplier(e.admin, po, d("50.00"), "Bank Transfer", "TT55");
        assertEquals(d("70.00"), suppliers.search(e.admin, null, false).get(0).balance());
        assertThrows(AppException.class, () -> purchases.paySupplier(e.admin, po, d("70.01"), "Cash", null));
    }

    @Test
    void inventoryOfficerCannotSeeSupplierBalancesOrPaySuppliers() {
        var e = new TestEnv();
        var suppliers = new SupplierService(e.db);
        suppliers.create(e.admin, "Acme", null, null, null, null);
        var inv = e.userWithRole("inv", Role.INVENTORY_OFFICER);
        assertNull(suppliers.search(inv, null, false).get(0).balance());
        assertThrows(AppException.class, () -> new PurchaseService(e.db).paySupplier(inv, 1, d("1"), "Cash", null));
    }

    @Test
    void customerFinancialDataIsProtected() {
        var e = new TestEnv();
        var customers = new CustomerService(e.db);
        long id = customers.create(e.admin, "Jane", "+263 77 000 1111", null, null, true, d("100"));
        var cashier = e.userWithRole("cash", Role.CASHIER);
        var seen = customers.search(cashier, "Jane", false).get(0);
        assertNull(seen.balance());
        assertNull(seen.creditLimit());
        assertThrows(AppException.class, () -> customers.statement(cashier, id, null, null));
        assertThrows(AppException.class, () -> customers.create(cashier, "X", null, null, null, false, null));
    }

    @Test
    void customerCreditStatementAndPayments() {
        var e = new TestEnv();
        var customers = new CustomerService(e.db);
        long cust = customers.create(e.admin, "Jane", null, null, null, true, d("500"));
        long p = e.product("Item", "1", "40.00", "10");
        e.checkout.checkout(e.admin, new CheckoutService.Request(List.of(new CheckoutService.Line(p, d("2"), null)), null, cust,
                List.of(new com.erolitechnologies.bizflow.domain.sale.PaymentLine("Customer Credit", d("80.00"), null))));
        customers.receivePayment(e.admin, cust, d("30.00"), "Cash", "part payment");
        assertThrows(AppException.class, () -> customers.receivePayment(e.admin, cust, d("60.00"), "Cash", null));
        var st = customers.statement(e.admin, cust, null, null);
        assertEquals(2, st.size());
        assertEquals(d("80.00"), st.get(0).runningBalance());
        assertEquals(d("50.00"), st.get(1).runningBalance());
        assertEquals(d("50.00"), customers.search(e.admin, "Jane", false).get(0).balance());
    }

    @Test
    void demoDataLoadsAndIncludesLowStockExample() {
        var e = new TestEnv(true);
        assertEquals(13, e.products.search(e.admin, null, null, false, false).size());
        assertTrue(e.products.search(e.admin, null, null, false, true).size() >= 1);
        assertTrue(scalar(e, "SELECT COUNT(*) FROM customers") >= 2);
    }

    @Test
    void migratorIsIdempotent() {
        var e = new TestEnv();
        assertEquals(0, new com.erolitechnologies.bizflow.infra.Migrator(e.db).migrate());
        assertEquals(1L, scalar(e, "SELECT COUNT(*) FROM schema_info"));
    }
}
