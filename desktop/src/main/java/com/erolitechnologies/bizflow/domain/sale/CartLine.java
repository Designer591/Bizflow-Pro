package com.erolitechnologies.bizflow.domain.sale;

import java.math.BigDecimal;

/** One cart line as entered at the till. lineDiscount is an absolute amount for the whole line. */
public record CartLine(
        long productId,
        String description,
        BigDecimal qty,
        BigDecimal unitPrice,
        BigDecimal unitCost,
        boolean taxExempt,
        BigDecimal lineDiscount) {

    public CartLine {
        if (qty == null || qty.signum() <= 0) throw new IllegalArgumentException("Quantity must be greater than zero.");
        if (unitPrice == null || unitPrice.signum() < 0) throw new IllegalArgumentException("Price cannot be negative.");
        if (unitCost == null || unitCost.signum() < 0) throw new IllegalArgumentException("Cost cannot be negative.");
        if (lineDiscount == null) lineDiscount = BigDecimal.ZERO;
        if (lineDiscount.signum() < 0) throw new IllegalArgumentException("Discount cannot be negative.");
    }
}
