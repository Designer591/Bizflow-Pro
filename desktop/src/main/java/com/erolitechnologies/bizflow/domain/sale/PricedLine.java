package com.erolitechnologies.bizflow.domain.sale;

import java.math.BigDecimal;

/** Result of pricing a CartLine: all amounts at scale 2. lineTotal is what the customer pays for the line. */
public record PricedLine(
        CartLine source,
        BigDecimal gross,       // qty * unitPrice before any discount
        BigDecimal discount,    // line discount + allocated cart discount
        BigDecimal tax,
        BigDecimal lineTotal,
        BigDecimal cost) {      // qty * unitCost, used for COGS
}
