package com.erolitechnologies.bizflow.service;

import com.erolitechnologies.bizflow.infra.AppException;
import com.erolitechnologies.bizflow.infra.Db;
import com.erolitechnologies.bizflow.security.Permission;
import com.erolitechnologies.bizflow.security.Session;

import java.util.List;

public final class BranchService {
    public record Branch(long id, String name, String code, String address, String phone, boolean main, boolean active) {}

    private final Db db;

    public BranchService(Db db) { this.db = db; }

    public List<Branch> list() {
        return db.read(c -> Db.query(c, "SELECT id, name, code, COALESCE(address,''), COALESCE(phone,''), is_main, active FROM branches ORDER BY is_main DESC, name",
                rs -> new Branch(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6) == 1, rs.getInt(7) == 1)));
    }

    public long create(Session s, String name, String code, String address, String phone) {
        s.require(Permission.BRANCH_MANAGE);
        Validate.required(name, "Branch name");
        String cd = Validate.required(code, "Branch code").toUpperCase();
        if (!cd.matches("[A-Z0-9]{2,8}")) throw new AppException("Branch code must be 2-8 letters or numbers.");
        Validate.phone(phone);
        return db.tx(c -> {
            long biz = Db.scalarLong(c, "SELECT MIN(id) FROM businesses");
            long id = Db.insert(c, "INSERT INTO branches(business_id, name, code, address, phone) VALUES (?,?,?,?,?)", biz, name.trim(), cd, Validate.optional(address), Validate.optional(phone));
            AuditService.log(c, s, "BRANCH_CREATED", "branch", id, cd);
            return id;
        });
    }

    public void update(Session s, long id, String name, String address, String phone, boolean active) {
        s.require(Permission.BRANCH_MANAGE);
        Validate.required(name, "Branch name");
        db.txVoid(c -> {
            if (!active && Db.scalarLong(c, "SELECT is_main FROM branches WHERE id = ?", id) == 1)
                throw new AppException("The main branch cannot be deactivated.");
            Db.update(c, "UPDATE branches SET name = ?, address = ?, phone = ?, active = ? WHERE id = ?", name.trim(), Validate.optional(address), Validate.optional(phone), active, id);
            AuditService.log(c, s, "BRANCH_UPDATED", "branch", id, null);
            return null;
        });
    }

    public long mainBranchId() {
        return db.read(c -> Db.scalarLong(c, "SELECT COALESCE(MIN(id),0) FROM branches WHERE is_main = 1"));
    }
}
