package com.erolitechnologies.bizflow.licensing;

import java.util.EnumSet;
import java.util.Set;

public enum Edition {
    STARTER(EnumSet.of(Feature.POS, Feature.CASH_SHIFTS, Feature.INVENTORY, Feature.CUSTOMERS, Feature.BASIC_REPORTS, Feature.BACKUP)),
    PROFESSIONAL(EnumSet.of(Feature.POS, Feature.CASH_SHIFTS, Feature.INVENTORY, Feature.CUSTOMERS, Feature.BASIC_REPORTS, Feature.BACKUP,
            Feature.SUPPLIERS_PURCHASING, Feature.FINANCE, Feature.EMPLOYEES_SHIFTS, Feature.ADVANCED_REPORTS, Feature.EXPORTS)),
    ENTERPRISE(EnumSet.allOf(Feature.class));

    private final Set<Feature> features;

    Edition(Set<Feature> features) { this.features = features; }

    public boolean includes(Feature f) { return features.contains(f); }

    public enum Feature {
        POS, CASH_SHIFTS, INVENTORY, CUSTOMERS, BASIC_REPORTS, BACKUP,
        SUPPLIERS_PURCHASING, FINANCE, EMPLOYEES_SHIFTS, ADVANCED_REPORTS, EXPORTS,
        MULTI_BRANCH
    }
}
