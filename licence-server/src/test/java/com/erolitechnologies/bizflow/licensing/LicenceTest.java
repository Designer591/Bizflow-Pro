package com.erolitechnologies.bizflow.licensing;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class LicenceTest {
    private final KeyPair keys = LicenceSigner.generateKeyPair();
    private final LicenceSigner signer = new LicenceSigner(keys.getPrivate());
    private final LicenceVerifier verifier = new LicenceVerifier(keys.getPublic());
    private final Instant now = Instant.parse("2026-06-01T00:00:00Z");

    private LicenceData annual(int devices, int graceDays) {
        return new LicenceData("LIC-1", Edition.PROFESSIONAL, LicenceData.Kind.ANNUAL, "Acme Stores",
                devices, now.minus(Duration.ofDays(300)), now.plus(Duration.ofDays(65)), graceDays, "*");
    }

    @Test
    void signedLicenceVerifiesOffline() {
        String token = signer.issue(annual(2, 14));
        LicenceData d = verifier.verify(token);
        assertEquals("Acme Stores", d.licensee());
        assertEquals(Edition.PROFESSIONAL, d.edition());
    }

    @Test
    void tamperedPayloadIsRejected() {
        String token = signer.issue(annual(1, 14));
        String[] p = token.split("\\.");
        String forgedPayload = new String(java.util.Base64.getUrlDecoder().decode(p[0]))
                .replace("edition=PROFESSIONAL", "edition=ENTERPRISE");
        String forged = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(forgedPayload.getBytes()) + "." + p[1];
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(forged));
        assertEquals(LicenceVerifier.State.INVALID, verifier.evaluate(forged, now, null, "dev-A").state());
    }

    @Test
    void licenceSignedWithAnotherKeyIsRejected() {
        KeyPair other = LicenceSigner.generateKeyPair();
        String token = new LicenceSigner(other.getPrivate()).issue(annual(1, 14));
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(token));
    }

    @Test
    void garbageIsRejectedWithoutCrashing() {
        assertEquals(LicenceVerifier.State.INVALID, verifier.evaluate("not-a-licence", now, null, "dev-A").state());
        assertEquals(LicenceVerifier.State.INVALID, verifier.evaluate("a.b", now, null, "dev-A").state());
        assertEquals(LicenceVerifier.State.INVALID, verifier.evaluate(null, now, null, "dev-A").state());
    }

    @Test
    void perpetualNeverExpires() {
        var d = new LicenceData("P-1", Edition.STARTER, LicenceData.Kind.PERPETUAL, "Shop", 1, now, null, 0, "*");
        var st = verifier.evaluate(signer.issue(d), now.plus(Duration.ofDays(9999)), null, "dev-A");
        assertEquals(LicenceVerifier.State.VALID, st.state());
    }

    @Test
    void expiryAndGracePeriod() {
        String token = signer.issue(annual(1, 14));
        Instant exp = now.plus(Duration.ofDays(65));
        assertEquals(LicenceVerifier.State.VALID, verifier.evaluate(token, now, null, "dev-A").state());
        assertEquals(LicenceVerifier.State.EXPIRING_SOON, verifier.evaluate(token, exp.minus(Duration.ofDays(3)), null, "dev-A").state());
        var grace = verifier.evaluate(token, exp.plus(Duration.ofDays(5)), null, "dev-A");
        assertEquals(LicenceVerifier.State.IN_GRACE, grace.state());
        assertTrue(grace.allowsUse());
        var expired = verifier.evaluate(token, exp.plus(Duration.ofDays(15)), null, "dev-A");
        assertEquals(LicenceVerifier.State.EXPIRED, expired.state());
        assertFalse(expired.allowsUse());
    }

    @Test
    void deviceBoundLicenceRejectsOtherComputers() {
        var d = new LicenceData("B-1", Edition.PROFESSIONAL, LicenceData.Kind.ANNUAL, "Acme", 2, now, now.plus(Duration.ofDays(100)), 14, "dev-A");
        String token = signer.issue(d);
        assertEquals(LicenceVerifier.State.VALID, verifier.evaluate(token, now, null, "dev-A").state());
        assertEquals(LicenceVerifier.State.DEVICE_MISMATCH, verifier.evaluate(token, now, null, "dev-B").state());
    }

    @Test
    void clockRollbackIsDetected() {
        String token = signer.issue(annual(1, 14));
        var st = verifier.evaluate(token, now.minus(Duration.ofDays(30)), now, "dev-A");
        assertEquals(LicenceVerifier.State.CLOCK_TAMPERED, st.state());
    }

    @Test
    void editionFeatureGating() {
        assertTrue(Edition.STARTER.includes(Edition.Feature.POS));
        assertFalse(Edition.STARTER.includes(Edition.Feature.FINANCE));
        assertTrue(Edition.PROFESSIONAL.includes(Edition.Feature.EXPORTS));
        assertFalse(Edition.PROFESSIONAL.includes(Edition.Feature.MULTI_BRANCH));
        assertTrue(Edition.ENTERPRISE.includes(Edition.Feature.MULTI_BRANCH));
    }

    @Test
    void nonPerpetualWithoutExpiryIsRefusedAtIssue() {
        assertThrows(IllegalArgumentException.class, () ->
                new LicenceData("X", Edition.STARTER, LicenceData.Kind.ANNUAL, "S", 1, now, null, 0, "*"));
    }
}
