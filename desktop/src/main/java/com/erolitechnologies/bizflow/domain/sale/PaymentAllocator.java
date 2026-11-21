package com.erolitechnologies.bizflow.domain.sale;

import com.erolitechnologies.bizflow.domain.Money;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates split payments. Change is only ever given from cash: non-cash tenders must not exceed
 * what is still owed after earlier tenders, and any overpayment must be absorbed by a cash line.
 */
public final class PaymentAllocator {
    public static final String CASH = "Cash";
    public static final String CREDIT = "Customer Credit";

    public record Result(List<AppliedPayment> applied, BigDecimal paid, BigDecimal change, BigDecimal creditAmount) {}

    public record AppliedPayment(String method, BigDecimal tendered, BigDecimal applied, BigDecimal change, String reference) {}

    private PaymentAllocator() {}

    public static Result allocate(BigDecimal totalDue, List<PaymentLine> tenders, boolean allowCredit) {
        if (tenders == null || tenders.isEmpty()) throw new IllegalArgumentException("Add at least one payment.");
        BigDecimal remaining = Money.round(totalDue);
        BigDecimal nonCashTotal = BigDecimal.ZERO;
        BigDecimal cashTotal = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (PaymentLine p : tenders) {
            BigDecimal amt = Money.round(p.amount());
            if (CREDIT.equalsIgnoreCase(p.method())) {
                if (!allowCredit) throw new IllegalArgumentException("Customer credit is not available for this sale.");
                credit = credit.add(amt);
            } else if (CASH.equalsIgnoreCase(p.method())) {
                cashTotal = cashTotal.add(amt);
            } else {
                nonCashTotal = nonCashTotal.add(amt);
            }
        }
        BigDecimal noChange = nonCashTotal.add(credit);
        if (noChange.compareTo(remaining) > 0)
            throw new IllegalArgumentException("Card, mobile money, transfer and credit payments cannot exceed the amount due.");
        BigDecimal paidTotal = noChange.add(cashTotal);
        if (paidTotal.compareTo(remaining) < 0)
            throw new IllegalArgumentException("Payments total " + paidTotal + " but " + remaining + " is due.");
        BigDecimal change = paidTotal.subtract(remaining);
        // change must come out of cash tendered
        if (change.compareTo(cashTotal) > 0) throw new IllegalArgumentException("Change cannot exceed cash tendered.");

        List<AppliedPayment> applied = new ArrayList<>();
        BigDecimal changeLeft = change;
        for (PaymentLine p : tenders) {
            BigDecimal amt = Money.round(p.amount());
            BigDecimal ch = Money.ZERO;
            if (CASH.equalsIgnoreCase(p.method()) && changeLeft.signum() > 0) {
                ch = changeLeft.min(amt);
                changeLeft = changeLeft.subtract(ch);
            }
            applied.add(new AppliedPayment(p.method(), amt, amt.subtract(ch), ch, p.reference()));
        }
        return new Result(List.copyOf(applied), Money.round(paidTotal), Money.round(change), Money.round(credit));
    }
}
