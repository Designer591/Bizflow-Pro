package com.erolitechnologies.bizflow.service;

import com.erolitechnologies.bizflow.infra.AppException;
import com.erolitechnologies.bizflow.infra.Db;
import com.erolitechnologies.bizflow.licensing.*;
import com.erolitechnologies.bizflow.security.Role;
import com.erolitechnologies.bizflow.testsupport.TestEnv;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class LicenceServiceTest {
    private static long scalar(TestEnv e, String sql) { return e.db.read(c -> Db.scalarLong(c, sql)); }

    private final KeyPair keys = LicenceSigner.generateKeyPair();
    private final LicenceSigner signer = new LicenceSigner(keys.getPrivate());

    private LicenceService svc(TestEnv e, Clock clock) {
        return new LicenceService(e.db, e.settings, new LicenceVerifier(keys.getPublic()), "BFP-TESTDEVICE0001", clock);
    }

    private String token(Edition ed, LicenceData.Kind kind, Instant issued, Instant expires, String device) {
        return signer.issue(new LicenceData("LIC-T1", ed, kind, "Acme", 1, issued, expires, 14, device));
    }

    @Test
    void freshInstallRunsEvaluationThenStopsAfterFourteenDays() {
        var e = new TestEnv();
        var st = svc(e, Clock.systemUTC()).status();
        assertTrue(st.evaluation());
        assertTrue(st.allowsUse());
        assertTrue(st.daysLeft() >= 13);
        var later = Clock.fixed(Instant.now().plus(Duration.ofDays(20)), ZoneOffset.UTC);
        var expired = svc(e, later).status();
        assertFalse(expired.allowsUse());
        assertEquals(LicenceVerifier.State.EXPIRED, expired.state());
    }

    @Test
    void installingASignedLicenceUnlocksItsEditionAndFeatures() {
        var e = new TestEnv();
        var s = svc(e, Clock.systemUTC());
        Instant now = Instant.now();
        var st = s.install(e.admin, token(Edition.STARTER, LicenceData.Kind.ANNUAL, now, now.plus(Duration.ofDays(365)), "BFP-TESTDEVICE0001"));
        assertEquals(Edition.STARTER, st.edition());
        assertFalse(st.evaluation());
        assertTrue(s.hasFeature(Edition.Feature.POS));
        assertFalse(s.hasFeature(Edition.Feature.FINANCE));
        assertThrows(AppException.class, () -> s.requireFeature(Edition.Feature.MULTI_BRANCH));
        assertEquals(1L, scalar(e, "SELECT COUNT(*) FROM licence_activations"));
        assertEquals(1L, scalar(e, "SELECT COUNT(*) FROM audit_logs WHERE action='LICENCE_INSTALLED'"));
    }

    @Test
    void forgedWrongDeviceExpiredAndUnauthorisedInstallsAreRejected() {
        var e = new TestEnv();
        var s = svc(e, Clock.systemUTC());
        Instant now = Instant.now();
        KeyPair other = LicenceSigner.generateKeyPair();
        String forged = new LicenceSigner(other.getPrivate()).issue(new LicenceData("X", Edition.ENTERPRISE, LicenceData.Kind.PERPETUAL, "Pirate", 99, now, null, 0, "*"));
        assertThrows(AppException.class, () -> s.install(e.admin, forged));
        assertThrows(AppException.class, () -> s.install(e.admin, token(Edition.PROFESSIONAL, LicenceData.Kind.ANNUAL, now, now.plus(Duration.ofDays(30)), "BFP-SOMEONEELSE99")));
        assertThrows(AppException.class, () -> s.install(e.admin, token(Edition.PROFESSIONAL, LicenceData.Kind.ANNUAL, now.minus(Duration.ofDays(500)), now.minus(Duration.ofDays(100)), "*")));
        assertThrows(AppException.class, () -> s.install(e.admin, "garbage"));
        var cashier = e.userWithRole("cash", Role.CASHIER);
        assertThrows(AppException.class, () -> s.install(cashier, token(Edition.STARTER, LicenceData.Kind.PERPETUAL, now, null, "*")));
        assertTrue(s.status().evaluation(), "nothing was installed");
    }

    @Test
    void expiredLicenceEntersGraceThenLocksAndRollingBackTheClockIsCaught() {
        var e = new TestEnv();
        Instant now = Instant.now();
        svc(e, Clock.systemUTC()).install(e.admin, token(Edition.PROFESSIONAL, LicenceData.Kind.MONTHLY, now.minus(Duration.ofDays(33)), now.minus(Duration.ofDays(3)), "*") );
        var grace = svc(e, Clock.systemUTC()).status();
        assertEquals(LicenceVerifier.State.IN_GRACE, grace.state());
        assertTrue(grace.allowsUse());
        var locked = svc(e, Clock.fixed(now.plus(Duration.ofDays(20)), ZoneOffset.UTC)).status();
        assertEquals(LicenceVerifier.State.EXPIRED, locked.state());
        assertFalse(locked.allowsUse());
        var tampered = svc(e, Clock.fixed(now.minus(Duration.ofDays(60)), ZoneOffset.UTC)).status();
        assertEquals(LicenceVerifier.State.CLOCK_TAMPERED, tampered.state());
    }

    @Test
    void onlineActivationRequiresHttpsExceptLocalhost() {
        var e = new TestEnv();
        var s = svc(e, Clock.systemUTC());
        var ex = assertThrows(AppException.class, () -> s.activateOnline(e.admin, "AAAAA-AAAAA-AAAAA-AAAAA", "http://licences.example.com"));
        assertTrue(ex.getMessage().contains("https"));
        assertThrows(AppException.class, () -> s.activateOnline(e.admin, " ", "https://x.example"));
    }

    @Test
    void deviceIdLooksRightAndIsStable() {
        String a = LicenceService.computeDeviceId();
        assertTrue(a.matches("BFP-[A-Z0-9]{8,40}"));
        assertEquals(a, LicenceService.computeDeviceId());
    }
}
