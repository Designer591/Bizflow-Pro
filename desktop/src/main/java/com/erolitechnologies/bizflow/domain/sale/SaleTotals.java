package com.erolitechnologies.bizflow.domain.sale;

import java.math.BigDecimal;
import java.util.List;

public record SaleTotals(
        List<PricedLine> lines,
        BigDecimal subtotal,   // sum of gross
        BigDecimal discount,   // all discounts
        BigDecimal tax,
        BigDecimal total,      // amount due
        BigDecimal cogs) {
}
