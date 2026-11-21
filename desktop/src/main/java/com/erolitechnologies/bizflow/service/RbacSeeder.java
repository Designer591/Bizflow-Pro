package com.erolitechnologies.bizflow.service;

import com.erolitechnologies.bizflow.infra.Db;
import com.erolitechnologies.bizflow.security.Permission;
import com.erolitechnologies.bizflow.security.Role;

import java.sql.Connection;
import java.sql.SQLException;

/** Ensures every role/permission exists. Default role permissions are only applied to roles that have none yet. */
public final class RbacSeeder {
    private RbacSeeder() {}

    public static void ensure(Db db) {
        db.txVoid(c -> { ensure(c); return null; });
    }

    public static void ensure(Connection c) throws SQLException {
        for (Permission p : Permission.values())
            Db.update(c, "INSERT OR IGNORE INTO permissions(code, description) VALUES (?,?)", p.name(), p.description());
        for (Role r : Role.values()) {
            Db.update(c, "INSERT OR IGNORE INTO roles(name) VALUES (?)", r.name());
            long roleId = Db.scalarLong(c, "SELECT id FROM roles WHERE name = ?", r.name());
            if (Db.scalarLong(c, "SELECT COUNT(*) FROM role_permissions WHERE role_id = ?", roleId) == 0) {
                for (Permission p : r.defaultPermissions())
                    Db.update(c, "INSERT OR IGNORE INTO role_permissions(role_id, permission_id) SELECT ?, id FROM permissions WHERE code = ?", roleId, p.name());
            }
        }
        // New permissions introduced by an upgrade are granted to roles that include them by default.
        for (Role r : Role.values()) {
            long roleId = Db.scalarLong(c, "SELECT id FROM roles WHERE name = ?", r.name());
            if (r == Role.ADMINISTRATOR) {
                Db.update(c, "INSERT OR IGNORE INTO role_permissions(role_id, permission_id) SELECT ?, id FROM permissions", roleId);
            }
        }
    }
}
