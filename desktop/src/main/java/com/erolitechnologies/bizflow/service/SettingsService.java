package com.erolitechnologies.bizflow.service;

import com.erolitechnologies.bizflow.infra.Db;
import com.erolitechnologies.bizflow.security.Permission;
import com.erolitechnologies.bizflow.security.Session;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Key/value business settings with safe defaults. */
public final class SettingsService {
    public static final Map<String, String> DEFAULTS = new LinkedHashMap<>();
    static {
        DEFAULTS.put("currency_code", "USD");
        DEFAULTS.put("currency_symbol", "$");
        DEFAULTS.put("tax_enabled", "false");
        DEFAULTS.put("tax_name", "VAT");
        DEFAULTS.put("tax_rate", "15");
        DEFAULTS.put("tax_inclusive", "false");
        DEFAULTS.put("receipt_header", "");
        DEFAULTS.put("receipt_footer", "Thank you for shopping with us!");
        DEFAULTS.put("receipt_paper", "80mm");
        DEFAULTS.put("receipt_show_logo", "true");
        DEFAULTS.put("receipt_prefix", "RCP");
        DEFAULTS.put("receipt_escpos", "false");
        DEFAULTS.put("receipt_printer", "");
        DEFAULTS.put("receipt_open_drawer", "false");
        DEFAULTS.put("allow_negative_stock", "false");
        DEFAULTS.put("max_discount_percent_cashier", "10");
        DEFAULTS.put("require_open_shift", "true");
        DEFAULTS.put("session_idle_minutes", "30");
        DEFAULTS.put("theme", "light");
        DEFAULTS.put("payment_methods", "Cash,EcoCash,Bank Transfer,Card");
        DEFAULTS.put("default_credit_limit", "0");
        DEFAULTS.put("backup_dir", "");
        DEFAULTS.put("backup_enabled", "true");
        DEFAULTS.put("backup_interval_hours", "24");
        DEFAULTS.put("backup_retention", "14");
        DEFAULTS.put("backup_last_ok", "");
        DEFAULTS.put("backup_last_error", "");
        DEFAULTS.put("update_check_enabled", "true");
        DEFAULTS.put("update_channel", "stable");
        DEFAULTS.put("update_url", "");
    }

    private final Db db;

    public SettingsService(Db db) { this.db = db; }

    public String get(String key) {
        return db.read(c -> get(c, key));
    }

    public static String get(Connection c, String key) throws SQLException {
        String v = Db.queryOne(c, "SELECT value FROM business_settings WHERE key = ?", rs -> rs.getString(1), key);
        return v != null ? v : DEFAULTS.getOrDefault(key, "");
    }

    public boolean getBool(String key) { return "true".equalsIgnoreCase(get(key)); }

    public static boolean getBool(Connection c, String key) throws SQLException { return "true".equalsIgnoreCase(get(c, key)); }

    public int getInt(String key) {
        try { return Integer.parseInt(get(key).trim()); } catch (NumberFormatException e) { return Integer.parseInt(DEFAULTS.getOrDefault(key, "0")); }
    }

    public static BigDecimal getDecimal(Connection c, String key) throws SQLException {
        try { return new BigDecimal(get(c, key).trim()); } catch (RuntimeException e) { return new BigDecimal(DEFAULTS.getOrDefault(key, "0")); }
    }

    public Map<String, String> all() {
        return db.read(c -> {
            Map<String, String> m = new LinkedHashMap<>(DEFAULTS);
            for (String[] kv : Db.query(c, "SELECT key, value FROM business_settings", rs -> new String[]{rs.getString(1), rs.getString(2)}))
                m.put(kv[0], kv[1]);
            return m;
        });
    }

    public static void put(Connection c, String key, String value) throws SQLException {
        Db.update(c, "INSERT INTO business_settings(key, value, updated_at) VALUES (?,?, datetime('now','localtime')) " +
                "ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at", key, value == null ? "" : value);
    }

    /** System-level write (backup status, theme preference) that needs no user permission. */
    public void putInternal(String key, String value) {
        db.txVoid(c -> { put(c, key, value); return null; });
    }

    public void update(Session s, Map<String, String> changes) {
        s.require(Permission.SETTINGS_MANAGE);
        validate(changes);
        db.txVoid(c -> {
            for (var e : changes.entrySet()) put(c, e.getKey(), e.getValue());
            AuditService.log(c, s, "SETTINGS_CHANGED", "settings", null, "Keys: " + String.join(", ", changes.keySet()));
            return null;
        });
    }

    private static void validate(Map<String, String> m) {
        if (m.containsKey("tax_rate")) {
            BigDecimal r = Validate.decimal(m.get("tax_rate"), "Tax rate");
            if (r.signum() < 0 || r.compareTo(new BigDecimal("100")) > 0) throw new com.erolitechnologies.bizflow.infra.AppException("Tax rate must be between 0 and 100.");
        }
        if (m.containsKey("max_discount_percent_cashier")) {
            BigDecimal r = Validate.decimal(m.get("max_discount_percent_cashier"), "Cashier discount limit");
            if (r.signum() < 0 || r.compareTo(new BigDecimal("100")) > 0) throw new com.erolitechnologies.bizflow.infra.AppException("Discount limit must be between 0 and 100.");
        }
        if (m.containsKey("receipt_paper") && !m.get("receipt_paper").matches("58mm|80mm|A4"))
            throw new com.erolitechnologies.bizflow.infra.AppException("Receipt paper must be 58mm, 80mm or A4.");
        if (m.containsKey("session_idle_minutes")) {
            int v = Validate.decimal(m.get("session_idle_minutes"), "Session timeout").intValue();
            if (v < 1 || v > 480) throw new com.erolitechnologies.bizflow.infra.AppException("Session timeout must be between 1 and 480 minutes.");
        }
        if (m.containsKey("payment_methods") && m.get("payment_methods").isBlank())
            throw new com.erolitechnologies.bizflow.infra.AppException("At least one payment method is required.");
    }
}
