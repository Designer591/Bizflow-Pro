package com.erolitechnologies.bizflow.security;

import java.util.EnumSet;
import java.util.Set;

import static com.erolitechnologies.bizflow.security.Permission.*;

/** Built-in roles and their default permissions. Administrators can later tailor each role's permissions. */
public enum Role {
    ADMINISTRATOR("Administrator", EnumSet.allOf(Permission.class)),
    MANAGER("Manager", EnumSet.of(DASHBOARD_VIEW, POS_SELL, POS_DISCOUNT, POS_DISCOUNT_LARGE, POS_VOID, POS_REFUND,
            SALES_HISTORY_VIEW, SALES_HISTORY_ALL, PRODUCT_VIEW, PRODUCT_MANAGE, STOCK_ADJUST, PURCHASE_MANAGE,
            SUPPLIER_MANAGE, SUPPLIER_FINANCE_VIEW, CUSTOMER_VIEW, CUSTOMER_MANAGE, CUSTOMER_FINANCE_VIEW,
            EXPENSE_MANAGE, FINANCE_VIEW, REPORT_VIEW, REPORT_EXPORT, EMPLOYEE_MANAGE, SHIFT_OWN, SHIFT_REVIEW, AUDIT_VIEW)),
    CASHIER("Cashier", EnumSet.of(POS_SELL, POS_DISCOUNT, SALES_HISTORY_VIEW, PRODUCT_VIEW, CUSTOMER_VIEW, SHIFT_OWN)),
    INVENTORY_OFFICER("Inventory officer", EnumSet.of(DASHBOARD_VIEW, PRODUCT_VIEW, PRODUCT_MANAGE, STOCK_ADJUST,
            PURCHASE_MANAGE, SUPPLIER_MANAGE, REPORT_VIEW)),
    ACCOUNTANT("Accountant", EnumSet.of(DASHBOARD_VIEW, FINANCE_VIEW, EXPENSE_MANAGE, REPORT_VIEW, REPORT_EXPORT,
            CUSTOMER_VIEW, CUSTOMER_FINANCE_VIEW, SUPPLIER_FINANCE_VIEW, SALES_HISTORY_VIEW, SALES_HISTORY_ALL));

    private final String label;
    private final Set<Permission> defaults;

    Role(String label, Set<Permission> defaults) { this.label = label; this.defaults = defaults; }

    public String label() { return label; }
    public Set<Permission> defaultPermissions() { return defaults; }
}
