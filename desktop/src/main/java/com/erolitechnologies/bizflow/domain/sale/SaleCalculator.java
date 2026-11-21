package com.erolitechnologies.bizflow.domain.sale;

import com.erolitechnologies.bizflow.domain.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure pricing logic: line discounts, cart discount (allocated across lines so totals always add up
 * to the cent), then tax. Supports tax-exclusive and tax-inclusive pricing.
 */
public final class SaleCalculator {
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final BigDecimal taxRatePercent;
    private final boolean pricesIncludeTax;

    public SaleCalculator(BigDecimal taxRatePercent, boolean pricesIncludeTax) {
        if (taxRatePercent == null || taxRatePercent.signum() < 0 || taxRatePercent.compareTo(HUNDRED) > 0)
            throw new IllegalArgumentException("Tax rate must be between 0 and 100.");
        this.taxRatePercent = taxRatePercent;
        this.pricesIncludeTax = pricesIncludeTax;
    }

    /** @param cartDiscount absolute discount applied across the whole cart (use percentToAmount first for % discounts) */
    public SaleTotals calculate(List<CartLine> lines, BigDecimal cartDiscount) {
        if (lines == null || lines.isEmpty()) throw new IllegalArgumentException("The cart is empty.");
        if (cartDiscount == null) cartDiscount = BigDecimal.ZERO;
        cartDiscount = Money.round(cartDiscount);
        if (cartDiscount.signum() < 0) throw new IllegalArgumentException("Discount cannot be negative.");

        int n = lines.size();
        BigDecimal[] gross = new BigDecimal[n];
        BigDecimal[] lineDisc = new BigDecimal[n];
        BigDecimal[] afterLine = new BigDecimal[n];
        BigDecimal sumAfterLine = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            CartLine l = lines.get(i);
            gross[i] = Money.round(l.qty().multiply(l.unitPrice()));
            lineDisc[i] = Money.round(l.lineDiscount());
            if (lineDisc[i].compareTo(gross[i]) > 0)
                throw new IllegalArgumentException("Discount on '" + l.description() + "' exceeds the line amount.");
            afterLine[i] = gross[i].subtract(lineDisc[i]);
            sumAfterLine = sumAfterLine.add(afterLine[i]);
        }
        if (cartDiscount.compareTo(sumAfterLine) > 0)
            throw new IllegalArgumentException("Discount cannot exceed the cart total.");

        BigDecimal[] cartShare = allocate(cartDiscount, afterLine, sumAfterLine);

        List<PricedLine> priced = new ArrayList<>(n);
        BigDecimal subtotal = BigDecimal.ZERO, discount = BigDecimal.ZERO, tax = BigDecimal.ZERO,
                total = BigDecimal.ZERO, cogs = BigDecimal.ZERO;
        BigDecimal rate = taxRatePercent.divide(HUNDRED, 10, RoundingMode.HALF_UP);

        for (int i = 0; i < n; i++) {
            CartLine l = lines.get(i);
            BigDecimal disc = lineDisc[i].add(cartShare[i]);
            BigDecimal payable = gross[i].subtract(disc);           // after all discounts
            BigDecimal lineTax;
            BigDecimal lineTotal;
            if (l.taxExempt() || rate.signum() == 0) {
                lineTax = Money.ZERO;
                lineTotal = payable;
            } else if (pricesIncludeTax) {
                BigDecimal net = Money.round(payable.divide(BigDecimal.ONE.add(rate), 10, RoundingMode.HALF_UP));
                lineTax = payable.subtract(net);
                lineTotal = payable;
            } else {
                lineTax = Money.round(payable.multiply(rate));
                lineTotal = payable.add(lineTax);
            }
            BigDecimal cost = Money.round(l.qty().multiply(l.unitCost()));
            priced.add(new PricedLine(l, gross[i], disc, lineTax, lineTotal, cost));
            subtotal = subtotal.add(gross[i]);
            discount = discount.add(disc);
            tax = tax.add(lineTax);
            total = total.add(lineTotal);
            cogs = cogs.add(cost);
        }
        return new SaleTotals(List.copyOf(priced), Money.round(subtotal), Money.round(discount),
                Money.round(tax), Money.round(total), Money.round(cogs));
    }

    public static BigDecimal percentToAmount(List<CartLine> lines, BigDecimal percent) {
        if (percent.signum() < 0 || percent.compareTo(HUNDRED) > 0)
            throw new IllegalArgumentException("Discount percentage must be between 0 and 100.");
        BigDecimal base = BigDecimal.ZERO;
        for (CartLine l : lines)
            base = base.add(Money.round(l.qty().multiply(l.unitPrice())).subtract(Money.round(l.lineDiscount())));
        return Money.percentOf(base, percent);
    }

    /** Largest-remainder allocation so the shares sum exactly to the amount. */
    static BigDecimal[] allocate(BigDecimal amount, BigDecimal[] weights, BigDecimal sumWeights) {
        int n = weights.length;
        BigDecimal[] out = new BigDecimal[n];
        for (int i = 0; i < n; i++) out[i] = Money.ZERO;
        if (amount.signum() == 0 || sumWeights.signum() == 0) return out;
        BigDecimal allocated = BigDecimal.ZERO;
        BigDecimal[] rem = new BigDecimal[n];
        for (int i = 0; i < n; i++) {
            BigDecimal exact = amount.multiply(weights[i]).divide(sumWeights, 10, RoundingMode.HALF_UP);
            out[i] = exact.setScale(2, RoundingMode.DOWN);
            rem[i] = exact.subtract(out[i]);
            allocated = allocated.add(out[i]);
        }
        BigDecimal left = amount.subtract(allocated);
        BigDecimal cent = new BigDecimal("0.01");
        while (left.signum() > 0) {
            int best = -1;
            for (int i = 0; i < n; i++) {
                if (out[i].add(cent).compareTo(weights[i]) > 0) continue; // never discount beyond line value
                if (best < 0 || rem[i].compareTo(rem[best]) > 0) best = i;
            }
            if (best < 0) break;
            out[best] = out[best].add(cent);
            rem[best] = BigDecimal.valueOf(-1);
            left = left.subtract(cent);
        }
        return out;
    }
}
