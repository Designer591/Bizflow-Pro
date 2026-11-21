package com.erolitechnologies.bizflow.domain.sale;

import java.math.BigDecimal;

/** One tender in a (possibly split) payment. */
public record PaymentLine(String method, BigDecimal amount, String reference) {
    public PaymentLine {
        if (method == null || method.isBlank()) throw new IllegalArgumentException("Payment method is required.");
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("Payment amount must be greater than zero.");
    }
}
