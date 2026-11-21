package com.erolitechnologies.bizflow.domain.sale;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SaleCalculatorTest {

    private static BigDecimal d(String s) { return new BigDecimal(s); }

    private static CartLine line(String qty, String price, String cost, boolean exempt, String disc) {
        return new CartLine(1, "Item", d(qty), d(price), d(cost), exempt, d(disc));
    }

    @Test
    void exclusiveTaxAddsOnTop() {
        var calc = new SaleCalculator(d("15"), false);
        var t = calc.calculate(List.of(line("2", "10.00", "6.00", false, "0")), null);
        assertEquals(d("20.00"), t.subtotal());
        assertEquals(d("3.00"), t.tax());
        assertEquals(d("23.00"), t.total());
        assertEquals(d("12.00"), t.cogs());
    }

    @Test
    void inclusiveTaxIsExtractedNotAdded() {
        var calc = new SaleCalculator(d("15"), true);
        var t = calc.calculate(List.of(line("1", "115.00", "50.00", false, "0")), null);
        assertEquals(d("115.00"), t.total());
        assertEquals(d("15.00"), t.tax());
    }

    @Test
    void taxExemptProductsAreNotTaxed() {
        var calc = new SaleCalculator(d("15"), false);
        var t = calc.calculate(List.of(
                line("1", "10.00", "5.00", true, "0"),
                line("1", "10.00", "5.00", false, "0")), null);
        assertEquals(d("1.50"), t.tax());
        assertEquals(d("21.50"), t.total());
    }

    @Test
    void lineDiscountReducesTaxBase() {
        var calc = new SaleCalculator(d("10"), false);
        var t = calc.calculate(List.of(line("1", "100.00", "40.00", false, "20.00")), null);
        assertEquals(d("20.00"), t.discount());
        assertEquals(d("8.00"), t.tax());
        assertEquals(d("88.00"), t.total());
    }

    @Test
    void cartDiscountAllocationSumsExactlyToTheCent() {
        var calc = new SaleCalculator(d("0"), false);
        var lines = List.of(
                line("1", "10.00", "1", false, "0"),
                line("1", "10.00", "1", false, "0"),
                line("1", "10.00", "1", false, "0"));
        var t = calc.calculate(lines, d("10.00"));
        BigDecimal sum = t.lines().stream().map(PricedLine::discount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(d("10.00"), sum);
        assertEquals(d("20.00"), t.total());
    }

    @Test
    void percentDiscountHelper() {
        var lines = List.of(line("2", "50.00", "20", false, "0"));
        assertEquals(d("10.00"), SaleCalculator.percentToAmount(lines, d("10")));
    }

    @Test
    void discountLargerThanCartIsRejected() {
        var calc = new SaleCalculator(d("0"), false);
        assertThrows(IllegalArgumentException.class,
                () -> calc.calculate(List.of(line("1", "5.00", "1", false, "0")), d("6.00")));
    }

    @Test
    void lineDiscountLargerThanLineIsRejected() {
        var calc = new SaleCalculator(d("0"), false);
        assertThrows(IllegalArgumentException.class,
                () -> calc.calculate(List.of(line("1", "5.00", "1", false, "5.01")), null));
    }

    @Test
    void emptyCartAndBadQuantityAreRejected() {
        var calc = new SaleCalculator(d("0"), false);
        assertThrows(IllegalArgumentException.class, () -> calc.calculate(List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> line("0", "5.00", "1", false, "0"));
    }

    @Test
    void fractionalQuantitiesWork() {
        var calc = new SaleCalculator(d("0"), false);
        var t = calc.calculate(List.of(line("1.250", "4.00", "2.00", false, "0")), null);
        assertEquals(d("5.00"), t.total());
        assertEquals(d("2.50"), t.cogs());
    }

    // ---- split payments ----

    @Test
    void splitCashAndEcoCashWithChange() {
        var r = PaymentAllocator.allocate(d("23.00"), List.of(
                new PaymentLine("EcoCash", d("10.00"), "REF1"),
                new PaymentLine("Cash", d("20.00"), null)), false);
        assertEquals(d("7.00"), r.change());
        assertEquals(d("13.00"), r.applied().get(1).applied());
    }

    @Test
    void exactPaymentHasNoChange() {
        var r = PaymentAllocator.allocate(d("10.00"), List.of(new PaymentLine("Card", d("10.00"), null)), false);
        assertEquals(d("0.00"), r.change());
    }

    @Test
    void underpaymentIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                PaymentAllocator.allocate(d("10.00"), List.of(new PaymentLine("Cash", d("9.99"), null)), false));
    }

    @Test
    void nonCashOverpaymentIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                PaymentAllocator.allocate(d("10.00"), List.of(new PaymentLine("Card", d("12.00"), null)), false));
    }

    @Test
    void customerCreditRequiresPermission() {
        assertThrows(IllegalArgumentException.class, () ->
                PaymentAllocator.allocate(d("10.00"), List.of(new PaymentLine(PaymentAllocator.CREDIT, d("10.00"), null)), false));
        var r = PaymentAllocator.allocate(d("10.00"), List.of(new PaymentLine(PaymentAllocator.CREDIT, d("10.00"), null)), true);
        assertEquals(d("10.00"), r.creditAmount());
    }

    @Test
    void moneyHelpersRoundTrip() {
        assertEquals(1999L, com.erolitechnologies.bizflow.domain.Money.toMinor(d("19.99")));
        assertEquals(d("19.99"), com.erolitechnologies.bizflow.domain.Money.fromMinor(1999));
        assertEquals(1250L, com.erolitechnologies.bizflow.domain.Money.qtyToMilli(d("1.25")));
    }
}
