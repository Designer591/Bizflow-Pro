package com.erolitechnologies.bizflow.service;

import com.erolitechnologies.bizflow.infra.AppException;
import com.erolitechnologies.bizflow.infra.Db;
import com.erolitechnologies.bizflow.security.PasswordHasher;
import com.erolitechnologies.bizflow.security.PasswordPolicy;
import com.erolitechnologies.bizflow.security.Role;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** First-launch setup: business identity, settings, main branch and the first administrator, all in one transaction. */
public final class SetupService {
    public record SetupData(
            String businessName, String businessType, String logoPath, String address, String phone, String email,
            String country, String currencyCode, String currencySymbol,
            boolean taxEnabled, String taxName, BigDecimal taxRate, boolean taxInclusive,
            String receiptHeader, String receiptFooter, String receiptPaper,
            String adminUsername, String adminFullName, String adminPassword, boolean loadDemoData) {}

    private final Db db;
    private final PasswordHasher hasher;

    public SetupService(Db db, PasswordHasher hasher) { this.db = db; this.hasher = hasher; }

    public boolean isSetupComplete() {
        return db.read(c -> Db.scalarLong(c, "SELECT COUNT(*) FROM businesses") > 0);
    }

    public void complete(SetupData d) {
        if (isSetupComplete()) throw new AppException("This installation has already been set up.");
        Validate.required(d.businessName(), "Business name");
        Validate.required(d.businessType(), "Business type");
        Validate.required(d.country(), "Country");
        Validate.required(d.currencyCode(), "Currency");
        Validate.email(d.email());
        Validate.phone(d.phone());
        String user = Validate.required(d.adminUsername(), "Administrator username");
        if (!user.matches("[A-Za-z0-9._-]{3,30}")) throw new AppException("Username must be 3-30 letters, numbers, dots, dashes or underscores.");
        Validate.required(d.adminFullName(), "Administrator name");
        PasswordPolicy.check(d.adminPassword());
        if (d.taxEnabled() && (d.taxRate() == null || d.taxRate().signum() < 0 || d.taxRate().compareTo(new BigDecimal("100")) > 0))
            throw new AppException("Tax rate must be between 0 and 100.");

        db.txVoid(c -> {
            RbacSeeder.ensure(c);
            long biz = Db.insert(c, "INSERT INTO businesses(name, business_type, logo_path, address, phone, email, country, currency_code) VALUES (?,?,?,?,?,?,?,?)",
                    d.businessName().trim(), d.businessType().trim(), Validate.optional(d.logoPath()), Validate.optional(d.address()),
                    Validate.optional(d.phone()), Validate.optional(d.email()), d.country().trim(), d.currencyCode().trim().toUpperCase());
            long branch = Db.insert(c, "INSERT INTO branches(business_id, name, code, address, phone, is_main) VALUES (?,?,?,?,?,1)",
                    biz, "Main Branch", "MAIN", Validate.optional(d.address()), Validate.optional(d.phone()));
            Map<String, String> s = new LinkedHashMap<>();
            s.put("currency_code", d.currencyCode().trim().toUpperCase());
            s.put("currency_symbol", d.currencySymbol() == null || d.currencySymbol().isBlank() ? d.currencyCode().trim() : d.currencySymbol().trim());
            s.put("tax_enabled", String.valueOf(d.taxEnabled()));
            s.put("tax_name", d.taxName() == null || d.taxName().isBlank() ? "VAT" : d.taxName().trim());
            s.put("tax_rate", d.taxRate() == null ? "0" : d.taxRate().toPlainString());
            s.put("tax_inclusive", String.valueOf(d.taxInclusive()));
            s.put("receipt_header", d.receiptHeader() == null ? "" : d.receiptHeader());
            s.put("receipt_footer", d.receiptFooter() == null || d.receiptFooter().isBlank() ? "Thank you for shopping with us!" : d.receiptFooter());
            s.put("receipt_paper", d.receiptPaper() == null ? "80mm" : d.receiptPaper());
            for (var e : s.entrySet()) SettingsService.put(c, e.getKey(), e.getValue());
            long uid = Db.insert(c, "INSERT INTO users(branch_id, username, password_hash, full_name) VALUES (?,?,?,?)",
                    branch, user, hasher.hash(d.adminPassword()), d.adminFullName().trim());
            Db.update(c, "INSERT INTO user_roles(user_id, role_id) SELECT ?, id FROM roles WHERE name = ?", uid, Role.ADMINISTRATOR.name());
            Db.update(c, "INSERT INTO employees(user_id, branch_id, full_name, job_title) VALUES (?,?,?,?)", uid, branch, d.adminFullName().trim(), "Administrator");
            AuditService.log(c, null, "SETUP_COMPLETED", "business", biz, d.businessName().trim());
            if (d.loadDemoData()) DemoData.load(c, branch, uid, hasher);
            return null;
        });
    }
}
