package com.erolitechnologies.bizflow.service;

import com.erolitechnologies.bizflow.infra.AppException;
import com.erolitechnologies.bizflow.infra.Db;
import com.erolitechnologies.bizflow.security.*;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

public final class AuthService {
    public static final int MAX_FAILED_LOGINS = 5;
    public static final int LOCK_MINUTES = 15;
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Db db;
    private final PasswordHasher hasher;
    private final SettingsService settings;

    public AuthService(Db db, PasswordHasher hasher, SettingsService settings) {
        this.db = db;
        this.hasher = hasher;
        this.settings = settings;
    }

    private record UserRow(long id, String username, String fullName, String hash, boolean active, boolean locked, long branchId, boolean mustChange) {}

    public Session login(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isEmpty())
            throw new AppException("Enter your username and password.");
        String u = username.trim();
        return db.tx(c -> {
            UserRow r = Db.queryOne(c,
                    "SELECT id, username, full_name, password_hash, active, " +
                    "(locked_until IS NOT NULL AND locked_until > datetime('now','localtime')), COALESCE(branch_id, 0), must_change_pw " +
                    "FROM users WHERE username = ? COLLATE NOCASE",
                    rs -> new UserRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5) == 1,
                            rs.getInt(6) == 1, rs.getLong(7), rs.getInt(8) == 1), u);
            if (r == null) {
                AuditService.log(c, null, "LOGIN_FAILED", "user", null, "Unknown username");
                c.commit();
                throw new AppException("Incorrect username or password.");
            }
            if (!r.active) throw new AppException("This account has been deactivated. Contact an administrator.");
            if (r.locked) throw new AppException("Too many failed attempts. This account is locked for " + LOCK_MINUTES + " minutes.");
            if (!hasher.verify(password, r.hash)) {
                Db.update(c, "UPDATE users SET failed_logins = failed_logins + 1 WHERE id = ?", r.id);
                long fails = Db.scalarLong(c, "SELECT failed_logins FROM users WHERE id = ?", r.id);
                if (fails >= MAX_FAILED_LOGINS)
                    Db.update(c, "UPDATE users SET locked_until = datetime('now','localtime','+" + LOCK_MINUTES + " minutes'), failed_logins = 0 WHERE id = ?", r.id);
                AuditService.log(c, null, "LOGIN_FAILED", "user", r.id, "Wrong password for " + r.username);
                c.commit(); // keep the failure count even though we now throw
                throw new AppException("Incorrect username or password.");
            }
            Db.update(c, "UPDATE users SET failed_logins = 0, locked_until = NULL, last_login_at = datetime('now','localtime') WHERE id = ?", r.id);
            Set<Permission> perms = loadPermissions(c, r.id);
            Set<String> roles = new HashSet<>(Db.query(c, "SELECT ro.name FROM user_roles ur JOIN roles ro ON ro.id = ur.role_id WHERE ur.user_id = ?", rs -> rs.getString(1), r.id));
            long branch = r.branchId != 0 ? r.branchId : Db.scalarLong(c, "SELECT COALESCE(MIN(id),0) FROM branches");
            Session s = new Session(r.id, r.username, r.fullName, branch, roles, perms,
                    Duration.ofMinutes(Math.max(1, Integer.parseInt(SettingsService.get(c, "session_idle_minutes")))));
            AuditService.log(c, s, "LOGIN", "user", r.id, null);
            return s;
        });
    }

    static Set<Permission> loadPermissions(Connection c, long userId) throws SQLException {
        Set<Permission> out = EnumSet.noneOf(Permission.class);
        for (String code : Db.query(c,
                "SELECT DISTINCT p.code FROM user_roles ur JOIN role_permissions rp ON rp.role_id = ur.role_id " +
                "JOIN permissions p ON p.id = rp.permission_id WHERE ur.user_id = ?", rs -> rs.getString(1), userId)) {
            try { out.add(Permission.valueOf(code)); } catch (IllegalArgumentException ignored) { }
        }
        return out;
    }

    public boolean mustChangePassword(long userId) {
        return db.read(c -> Db.scalarLong(c, "SELECT must_change_pw FROM users WHERE id = ?", userId)) == 1;
    }

    public void logout(Session s) {
        if (s == null) return;
        try { db.txVoid(c -> { AuditService.log(c, s, "LOGOUT", "user", s.userId(), null); return null; }); }
        finally { s.end(); }
    }

    public void changePassword(Session s, String oldPassword, String newPassword) {
        s.touch();
        PasswordPolicy.check(newPassword);
        db.txVoid(c -> {
            String hash = Db.queryOne(c, "SELECT password_hash FROM users WHERE id = ?", rs -> rs.getString(1), s.userId());
            if (hash == null || !hasher.verify(oldPassword, hash)) throw new AppException("Your current password is not correct.");
            Db.update(c, "UPDATE users SET password_hash = ?, must_change_pw = 0 WHERE id = ?", hasher.hash(newPassword), s.userId());
            AuditService.log(c, s, "PASSWORD_CHANGED", "user", s.userId(), null);
            return null;
        });
    }

    /** Administrator issues a one-time recovery code to a user who has forgotten their password. */
    public String createRecoveryCode(Session admin, long userId) {
        admin.require(Permission.USER_MANAGE);
        return db.tx(c -> {
            String code = issueCode(c, userId);
            AuditService.log(c, admin, "RECOVERY_CODE_ISSUED", "user", userId, null);
            return code;
        });
    }

    /** For the "forgot admin password" path: only usable by someone with access to the machine (CLI flag). */
    public String createRecoveryCodeLocal(String username) {
        return db.tx(c -> {
            Long id = Db.queryOne(c, "SELECT id FROM users WHERE username = ? COLLATE NOCASE", rs -> rs.getLong(1), username);
            if (id == null) throw new AppException("No user with that name.");
            String code = issueCode(c, id);
            AuditService.log(c, null, "RECOVERY_CODE_ISSUED_LOCAL", "user", id, null);
            return code;
        });
    }

    private String issueCode(Connection c, long userId) throws SQLException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        String code = sb.toString();
        Db.update(c, "UPDATE users SET recovery_hash = ?, recovery_expires = datetime('now','localtime','+30 minutes') WHERE id = ?",
                hasher.hash(code), userId);
        return code.substring(0, 4) + "-" + code.substring(4);
    }

    public void resetPasswordWithCode(String username, String code, String newPassword) {
        PasswordPolicy.check(newPassword);
        String clean = code == null ? "" : code.replace("-", "").trim().toUpperCase();
        db.txVoid(c -> {
            record R(long id, String hash, boolean valid) {}
            R r = Db.queryOne(c, "SELECT id, recovery_hash, (recovery_expires IS NOT NULL AND recovery_expires > datetime('now','localtime')) " +
                    "FROM users WHERE username = ? COLLATE NOCASE", rs -> new R(rs.getLong(1), rs.getString(2), rs.getInt(3) == 1), username == null ? "" : username.trim());
            if (r == null || r.hash == null || !r.valid || !hasher.verify(clean, r.hash))
                throw new AppException("That recovery code is not valid or has expired.");
            Db.update(c, "UPDATE users SET password_hash = ?, recovery_hash = NULL, recovery_expires = NULL, failed_logins = 0, locked_until = NULL, must_change_pw = 0 WHERE id = ?",
                    hasher.hash(newPassword), r.id);
            AuditService.log(c, null, "PASSWORD_RESET_WITH_CODE", "user", r.id, null);
            return null;
        });
    }
}
