package com.erolitechnologies.bizflow.service;

import com.erolitechnologies.bizflow.infra.AppException;
import com.erolitechnologies.bizflow.infra.Db;
import com.erolitechnologies.bizflow.security.*;
import com.erolitechnologies.bizflow.testsupport.TestEnv;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceTest {
    private static long scalar(TestEnv e, String sql) { return e.db.read(c -> Db.scalarLong(c, sql)); }

    @Test
    void setupCreatesAdminBranchAndSettings() {
        var e = new TestEnv();
        assertTrue(e.setup.isSetupComplete());
        assertTrue(e.admin.has(Permission.USER_MANAGE));
        assertEquals(1L, scalar(e, "SELECT COUNT(*) FROM branches WHERE is_main = 1"));
        assertEquals("USD", e.settings.get("currency_code"));
        assertThrows(AppException.class, () -> e.setup.complete(new SetupService.SetupData("X", "Retail", null, null, null, null, "Z", "USD", "$", false, null, null, false, null, null, null, "bob", "Bob", "Passw0rd!", false)));
    }

    @Test
    void passwordIsNeverStoredInPlainText() {
        var e = new TestEnv();
        String stored = e.db.read(c -> Db.queryOne(c, "SELECT password_hash FROM users WHERE username='admin'", rs -> rs.getString(1)));
        assertFalse(stored.contains("Passw0rd!"));
    }

    @Test
    void wrongPasswordAndUnknownUserGiveTheSameMessage() {
        var e = new TestEnv();
        var a = assertThrows(AppException.class, () -> e.auth.login("admin", "wrong"));
        var b = assertThrows(AppException.class, () -> e.auth.login("nobody", "wrong"));
        assertEquals(a.getMessage(), b.getMessage());
    }

    @Test
    void accountLocksAfterRepeatedFailuresEvenWithCorrectPasswordAfterwards() {
        var e = new TestEnv();
        for (int i = 0; i < AuthService.MAX_FAILED_LOGINS; i++) assertThrows(AppException.class, () -> e.auth.login("admin", "bad"));
        var ex = assertThrows(AppException.class, () -> e.auth.login("admin", "Passw0rd!"));
        assertTrue(ex.getMessage().contains("locked"));
    }

    @Test
    void deactivatedUsersCannotLogInAndLastAdminIsProtected() {
        var e = new TestEnv();
        e.userWithRole("cash1", Role.CASHIER);
        long id = e.db.read(c -> Db.scalarLong(c, "SELECT id FROM users WHERE username='cash1'"));
        e.users.setActive(e.admin, id, false);
        assertThrows(AppException.class, () -> e.auth.login("cash1", "Passw0rd!"));
        e.users.setActive(e.admin, id, true);
        e.auth.login("cash1", "Passw0rd!");
        long adminId = e.admin.userId();
        assertThrows(AppException.class, () -> e.users.setActive(e.admin, adminId, false));
        assertThrows(AppException.class, () -> e.users.update(e.admin, adminId, "Admin", null, null, Role.CASHIER, null));
    }

    @Test
    void changePasswordRequiresOldPasswordAndPolicy() {
        var e = new TestEnv();
        assertThrows(AppException.class, () -> e.auth.changePassword(e.admin, "nope", "NewPass123"));
        assertThrows(AppException.class, () -> e.auth.changePassword(e.admin, "Passw0rd!", "short"));
        assertThrows(AppException.class, () -> e.auth.changePassword(e.admin, "Passw0rd!", "onlyletters"));
        e.auth.changePassword(e.admin, "Passw0rd!", "NewPass123");
        e.auth.login("admin", "NewPass123");
        assertThrows(AppException.class, () -> e.auth.login("admin", "Passw0rd!"));
    }

    @Test
    void recoveryCodeResetsPasswordOnceOnly() {
        var e = new TestEnv();
        String code = e.auth.createRecoveryCodeLocal("admin");
        assertThrows(AppException.class, () -> e.auth.resetPasswordWithCode("admin", "AAAA-BBBB", "BrandNew123"));
        e.auth.resetPasswordWithCode("admin", code, "BrandNew123");
        e.auth.login("admin", "BrandNew123");
        assertThrows(AppException.class, () -> e.auth.resetPasswordWithCode("admin", code, "Another1234"), "code is single-use");
    }

    @Test
    void sessionExpiresAfterIdleTimeoutAndLogoutEndsIt() {
        var s = new Session(1, "u", "U", 1, java.util.Set.of("CASHIER"), java.util.Set.of(Permission.POS_SELL), java.time.Duration.ofMillis(-1));
        assertTrue(s.isExpired());
        assertThrows(AppException.class, () -> s.require(Permission.POS_SELL));
        var e = new TestEnv();
        e.auth.logout(e.admin);
        assertThrows(AppException.class, () -> e.admin.require(Permission.POS_SELL));
    }

    @Test
    void cashierPermissionsMatchSpec() {
        var e = new TestEnv();
        var c = e.userWithRole("cash1", Role.CASHIER);
        assertTrue(c.has(Permission.POS_SELL));
        assertFalse(c.has(Permission.FINANCE_VIEW));
        assertFalse(c.has(Permission.EMPLOYEE_MANAGE));
        assertFalse(c.has(Permission.SETTINGS_MANAGE));
        assertFalse(c.has(Permission.CUSTOMER_FINANCE_VIEW));
    }

    @Test
    void administratorCanTailorRolePermissionsAndItTakesEffectOnNextLogin() {
        var e = new TestEnv();
        var set = new java.util.HashSet<>(e.users.permissionsOf(Role.CASHIER));
        set.add(Permission.POS_REFUND);
        e.users.setRolePermissions(e.admin, Role.CASHIER, set);
        e.userWithRole("cash1", Role.CASHIER);
        assertTrue(e.auth.login("cash1", "Passw0rd!").has(Permission.POS_REFUND));
        assertThrows(AppException.class, () -> e.users.setRolePermissions(e.admin, Role.ADMINISTRATOR, java.util.Set.of()));
    }

    @Test
    void importantActionsAreAudited() {
        var e = new TestEnv();
        e.userWithRole("cash1", Role.CASHIER);
        assertTrue(scalar(e, "SELECT COUNT(*) FROM audit_logs WHERE action='USER_CREATED'") >= 1);
        assertTrue(scalar(e, "SELECT COUNT(*) FROM audit_logs WHERE action='LOGIN'") >= 2);
        assertEquals(0L, scalar(e, "SELECT COUNT(*) FROM audit_logs WHERE details LIKE '%Passw0rd%'"), "passwords must never reach the audit log");
        assertFalse(e.auth == null);
        assertThrows(AppException.class, () -> new AuditService(e.db).search(e.userWithRole("cash2", Role.CASHIER), "", null, null, 10));
    }

    @Test
    void inputValidation() {
        var e = new TestEnv();
        assertThrows(AppException.class, () -> e.users.create(e.admin, "a b", "X", null, null, "Passw0rd!", Role.CASHIER, null));
        assertThrows(AppException.class, () -> e.users.create(e.admin, "okname", "", null, null, "Passw0rd!", Role.CASHIER, null));
        assertThrows(AppException.class, () -> e.users.create(e.admin, "okname", "X", "not-an-email", null, "Passw0rd!", Role.CASHIER, null));
        e.users.create(e.admin, "okname", "X", null, null, "Passw0rd!", Role.CASHIER, null);
        assertThrows(AppException.class, () -> e.users.create(e.admin, "okname", "X", null, null, "Passw0rd!", Role.CASHIER, null), "duplicate username");
    }

    @Test
    void employeeProfilesFollowUserAccountsAndShowActivity() {
        var e = new TestEnv();
        var employees = new EmployeeService(e.db);
        var cashier = e.userWithRole("cash1", Role.CASHIER);
        var list = employees.list(e.admin);
        assertEquals(2, list.size());
        var emp = list.stream().filter(x -> x.username().equals("cash1")).findFirst().get();
        employees.updateProfile(e.admin, emp.id(), "Senior cashier", "63-123456A12", "2026-03-15");
        assertEquals("Senior cashier", employees.list(e.admin).stream().filter(x -> x.username().equals("cash1")).findFirst().get().jobTitle());
        assertThrows(AppException.class, () -> employees.updateProfile(e.admin, emp.id(), "x", null, "15/03/2026"));
        assertTrue(employees.activity(e.admin, cashier.userId(), 10).size() >= 1);
        assertThrows(AppException.class, () -> employees.list(cashier));
    }
}
