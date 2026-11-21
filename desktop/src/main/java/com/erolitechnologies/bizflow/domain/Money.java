package com.erolitechnologies.bizflow.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Central money helpers. All financial maths in BizFlow uses BigDecimal at scale 2 (HALF_UP). */
public final class Money {
    public static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal THOUSAND = new BigDecimal("1000");

    private Money() {}

    public static BigDecimal of(String value) {
        return round(new BigDecimal(value));
    }

    public static BigDecimal round(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    /** Convert to integer minor units (cents) for storage. */
    public static long toMinor(BigDecimal v) {
        return round(v).movePointRight(2).longValueExact();
    }

    public static BigDecimal fromMinor(long minor) {
        return BigDecimal.valueOf(minor, 2);
    }

    /** Quantities are stored as integer thousandths so fractional units (kg, m) work. */
    public static long qtyToMilli(BigDecimal qty) {
        return qty.setScale(3, RoundingMode.HALF_UP).movePointRight(3).longValueExact();
    }

    public static BigDecimal qtyFromMilli(long milli) {
        return BigDecimal.valueOf(milli, 3);
    }

    public static BigDecimal percentOf(BigDecimal amount, BigDecimal percent) {
        return round(amount.multiply(percent).divide(HUNDRED, 10, RoundingMode.HALF_UP));
    }
}
