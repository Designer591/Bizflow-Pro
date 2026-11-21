package com.erolitechnologies.bizflow.service;

import com.erolitechnologies.bizflow.infra.Db;
import com.erolitechnologies.bizflow.security.Permission;
import com.erolitechnologies.bizflow.security.Session;

public final class BusinessService {
    public record Profile(long id, String name, String type, String logoPath, String address, String phone, String email, String country, String currencyCode) {}

    private final Db db;

    public BusinessService(Db db) { this.db = db; }

    public Profile get() {
        return db.read(c -> Db.queryOne(c, "SELECT id, name, business_type, COALESCE(logo_path,''), COALESCE(address,''), COALESCE(phone,''), COALESCE(email,''), country, currency_code FROM businesses ORDER BY id LIMIT 1",
                rs -> new Profile(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9))));
    }

    public void update(Session s, Profile p) {
        s.require(Permission.SETTINGS_MANAGE);
        Validate.required(p.name(), "Business name");
        Validate.required(p.type(), "Business type");
        Validate.required(p.country(), "Country");
        Validate.email(p.email());
        Validate.phone(p.phone());
        db.txVoid(c -> {
            Db.update(c, "UPDATE businesses SET name=?, business_type=?, logo_path=?, address=?, phone=?, email=?, country=?, currency_code=? WHERE id = ?",
                    p.name().trim(), p.type().trim(), Validate.optional(p.logoPath()), Validate.optional(p.address()), Validate.optional(p.phone()),
                    Validate.optional(p.email()), p.country().trim(), p.currencyCode().trim().toUpperCase(), p.id());
            AuditService.log(c, s, "BUSINESS_UPDATED", "business", p.id(), null);
            return null;
        });
    }
}
