package com.erolitechnologies.bizflow.service;

import com.erolitechnologies.bizflow.infra.AppException;
import com.erolitechnologies.bizflow.infra.Db;
import com.erolitechnologies.bizflow.security.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

public final class UserService {
    public record UserInfo(long id, String username, String fullName, String email, String phone, boolean active,
                           String role, Long branchId, String lastLogin) {}

    private final Db db;
    private final PasswordHasher hasher;

    public UserService(Db db, PasswordHasher hasher) { this.db = db; this.hasher = hasher; }

    public List<UserInfo> list(Session s) {
        s.require(Permission.USER_MANAGE);
        return db.read(c -> Db.query(c,
                "SELECT u.id, u.username, u.full_name, COALESCE(u.email,''), COALESCE(u.phone,''), u.active, " +
                "COALESCE((SELECT ro.name FROM user_roles ur JOIN roles ro ON ro.id = ur.role_id WHERE ur.user_id = u.id LIMIT 1),''), " +
                "u.branch_id, COALESCE(u.last_login_at,'') FROM users u ORDER BY u.username",
                rs -> new UserInfo(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getInt(6) == 1, rs.getString(7), (Long) rs.getObject(8), rs.getString(9))));
    }

    public long create(Session s, String username, String fullName, String email, String phone, String password, Role role, Long branchId) {
        s.require(Permission.USER_MANAGE);
        String u = Validate.required(username, "Username");
        if (!u.matches("[A-Za-z0-9._-]{3,30}")) throw new AppException("Username must be 3-30 letters, numbers, dots, dashes or underscores.");
        Validate.required(fullName, "Full name");
        Validate.email(email);
        Validate.phone(phone);
        PasswordPolicy.check(password);
        return db.tx(c -> {
            long id = Db.insert(c, "INSERT INTO users(branch_id, username, password_hash, full_name, email, phone, must_change_pw) VALUES (?,?,?,?,?,?,1)",
                    branchId == null ? s.branchId() : branchId, u, hasher.hash(password), fullName.trim(), Validate.optional(email), Validate.optional(phone));
            assignRole(c, id, role);
            Db.update(c, "INSERT INTO employees(user_id, branch_id, full_name, email, phone, job_title) VALUES (?,?,?,?,?,?)",
                    id, branchId == null ? s.branchId() : branchId, fullName.trim(), Validate.optional(email), Validate.optional(phone), role.label());
            AuditService.log(c, s, "USER_CREATED", "user", id, "username=" + u + ", role=" + role.name());
            return id;
        });
    }

    public void update(Session s, long id, String fullName, String email, String phone, Role role, Long branchId) {
        s.require(Permission.USER_MANAGE);
        Validate.required(fullName, "Full name");
        Validate.email(email);
        Validate.phone(phone);
        db.txVoid(c -> {
            boolean wasAdmin = isAdmin(c, id);
            if (wasAdmin && role != Role.ADMINISTRATOR && countActiveAdmins(c) <= 1)
                throw new AppException("You cannot remove the last active administrator.");
            Db.update(c, "UPDATE users SET full_name = ?, email = ?, phone = ?, branch_id = COALESCE(?, branch_id) WHERE id = ?",
                    fullName.trim(), Validate.optional(email), Validate.optional(phone), branchId, id);
            assignRole(c, id, role);
            Db.update(c, "UPDATE employees SET full_name = ?, email = ?, phone = ? WHERE user_id = ?", fullName.trim(), Validate.optional(email), Validate.optional(phone), id);
            AuditService.log(c, s, "USER_UPDATED", "user", id, "role=" + role.name());
            return null;
        });
    }

    public void setActive(Session s, long id, boolean active) {
        s.require(Permission.USER_MANAGE);
        if (!active && id == s.userId()) throw new AppException("You cannot deactivate your own account.");
        db.txVoid(c -> {
            if (!active && isAdmin(c, id) && countActiveAdmins(c) <= 1)
                throw new AppException("You cannot deactivate the last active administrator.");
            Db.update(c, "UPDATE users SET active = ? WHERE id = ?", active, id);
            AuditService.log(c, s, active ? "USER_ACTIVATED" : "USER_DEACTIVATED", "user", id, null);
            return null;
        });
    }

    public void adminResetPassword(Session s, long id, String newPassword) {
        s.require(Permission.USER_MANAGE);
        PasswordPolicy.check(newPassword);
        db.txVoid(c -> {
            Db.update(c, "UPDATE users SET password_hash = ?, must_change_pw = 1, failed_logins = 0, locked_until = NULL WHERE id = ?", hasher.hash(newPassword), id);
            AuditService.log(c, s, "PASSWORD_RESET_BY_ADMIN", "user", id, null);
            return null;
        });
    }

    // ---- role permission editing ----
    public Set<Permission> permissionsOf(Role role) {
        return db.read(c -> {
            Set<Permission> out = java.util.EnumSet.noneOf(Permission.class);
            for (String code : Db.query(c, "SELECT p.code FROM role_permissions rp JOIN roles r ON r.id = rp.role_id JOIN permissions p ON p.id = rp.permission_id WHERE r.name = ?",
                    rs -> rs.getString(1), role.name())) out.add(Permission.valueOf(code));
            return out;
        });
    }

    public void setRolePermissions(Session s, Role role, Set<Permission> perms) {
        s.require(Permission.USER_MANAGE);
        if (role == Role.ADMINISTRATOR) throw new AppException("The Administrator role always has every permission.");
        db.txVoid(c -> {
            long roleId = Db.scalarLong(c, "SELECT id FROM roles WHERE name = ?", role.name());
            Db.update(c, "DELETE FROM role_permissions WHERE role_id = ?", roleId);
            for (Permission p : perms)
                Db.update(c, "INSERT INTO role_permissions(role_id, permission_id) SELECT ?, id FROM permissions WHERE code = ?", roleId, p.name());
            AuditService.log(c, s, "ROLE_PERMISSIONS_CHANGED", "role", role.name(), perms.size() + " permissions");
            return null;
        });
    }

    private static void assignRole(Connection c, long userId, Role role) throws SQLException {
        Db.update(c, "DELETE FROM user_roles WHERE user_id = ?", userId);
        Db.update(c, "INSERT INTO user_roles(user_id, role_id) SELECT ?, id FROM roles WHERE name = ?", userId, role.name());
    }

    private static boolean isAdmin(Connection c, long userId) throws SQLException {
        return Db.scalarLong(c, "SELECT COUNT(*) FROM user_roles ur JOIN roles r ON r.id = ur.role_id WHERE ur.user_id = ? AND r.name = 'ADMINISTRATOR'", userId) > 0;
    }

    private static long countActiveAdmins(Connection c) throws SQLException {
        return Db.scalarLong(c, "SELECT COUNT(*) FROM users u JOIN user_roles ur ON ur.user_id = u.id JOIN roles r ON r.id = ur.role_id WHERE r.name = 'ADMINISTRATOR' AND u.active = 1");
    }
}
