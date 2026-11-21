package com.erolitechnologies.bizflow.service;

import com.erolitechnologies.bizflow.infra.AppException;

import java.math.BigDecimal;

/** Small shared input-validation helpers that produce business-friendly messages. */
public final class Validate {
    private Validate() {}

    public static String required(String v, String label) {
        if (v == null || v.isBlank()) throw new AppException(label + " is required.");
        return v.trim();
    }

    public static String maxLen(String v, int max, String label) {
        if (v != null && v.length() > max) throw new AppException(label + " is too long (maximum " + max + " characters).");
        return v;
    }

    public static String optional(String v) { return v == null || v.isBlank() ? null : v.trim(); }

    public static BigDecimal nonNegative(BigDecimal v, String label) {
        if (v == null) throw new AppException(label + " is required.");
        if (v.signum() < 0) throw new AppException(label + " cannot be negative.");
        return v;
    }

    public static BigDecimal positive(BigDecimal v, String label) {
        if (v == null || v.signum() <= 0) throw new AppException(label + " must be greater than zero.");
        return v;
    }

    public static void email(String v) {
        if (v != null && !v.isBlank() && !v.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))
            throw new AppException("Email address is not valid.");
    }

    public static void phone(String v) {
        if (v != null && !v.isBlank() && !v.matches("^[+0-9 ()\\-]{5,20}$"))
            throw new AppException("Phone number is not valid.");
    }

    public static BigDecimal decimal(String text, String label) {
        try {
            return new BigDecimal(text.trim().replace(",", ""));
        } catch (RuntimeException e) {
            throw new AppException(label + " must be a number.");
        }
    }
}
