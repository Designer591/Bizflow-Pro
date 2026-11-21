package com.erolitechnologies.bizflow.security;

public enum Permission {
    DASHBOARD_VIEW("View the dashboard"),
    POS_SELL("Make sales at the till"),
    POS_DISCOUNT("Apply discounts"),
    POS_DISCOUNT_LARGE("Apply discounts above the cashier limit"),
    POS_VOID("Void completed sales"),
    POS_REFUND("Refund sales"),
    SALES_HISTORY_VIEW("View sales history and reprint receipts"),
    SALES_HISTORY_ALL("View every cashier's sales (not only your own)"),
    PRODUCT_VIEW("View products and stock"),
    PRODUCT_MANAGE("Create, edit and archive products"),
    STOCK_ADJUST("Adjust and transfer stock"),
    PURCHASE_MANAGE("Create purchase orders and receive goods"),
    SUPPLIER_MANAGE("Manage suppliers"),
    SUPPLIER_FINANCE_VIEW("View supplier balances and pay suppliers"),
    CUSTOMER_VIEW("View customers"),
    CUSTOMER_MANAGE("Create and edit customers"),
    CUSTOMER_FINANCE_VIEW("View customer balances, statements and take account payments"),
    EXPENSE_MANAGE("Record and view expenses"),
    FINANCE_VIEW("View financial summaries"),
    REPORT_VIEW("View reports"),
    REPORT_EXPORT("Export reports to PDF and Excel"),
    EMPLOYEE_MANAGE("Manage employees"),
    USER_MANAGE("Manage user accounts and role permissions"),
    SHIFT_OWN("Open and close your own cash shift"),
    SHIFT_REVIEW("Review all cashier shifts and activity"),
    SETTINGS_MANAGE("Change system settings"),
    BACKUP_MANAGE("Create and restore backups"),
    LICENCE_MANAGE("Manage the software licence"),
    AUDIT_VIEW("View the audit log"),
    BRANCH_MANAGE("Manage branches");

    private final String description;
    Permission(String description) { this.description = description; }
    public String description() { return description; }
}
