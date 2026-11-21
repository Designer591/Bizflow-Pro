package com.erolitechnologies.bizflow.licensing;

import com.erolitechnologies.bizflow.licenceserver.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class LicenceServerTest {
    /** A clock the test can move forward. */
    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return now; }
    }

    private final KeyPair keys = LicenceSigner.generateKeyPair();
    private final LicenceVerifier verifier = new LicenceVerifier(keys.getPublic());
    private final TestClock clock = new TestClock();

    private LicenceManager manager() throws Exception {
        return new LicenceManager(new LicenceStore(Files.createTempDirectory("lic").resolve("licences.db")), new LicenceSigner(keys.getPrivate()), clock);
    }

    @Test
    void activationIsDeviceBoundVerifiesOfflineAndEnforcesDeviceLimit() throws Exception {
        var m = manager();
        var issued = m.issue("Acme Stores", Edition.PROFESSIONAL, LicenceData.Kind.ANNUAL, 2, 365, 14);
        String t1 = m.activate(issued.licenceKey(), "DEVICE-AAAAAAAA");
        var st = verifier.evaluate(t1, clock.instant(), null, "DEVICE-AAAAAAAA");
        assertEquals(LicenceVerifier.State.VALID, st.state());
        assertEquals(Edition.PROFESSIONAL, st.data().edition());
        assertEquals(LicenceVerifier.State.DEVICE_MISMATCH, verifier.evaluate(t1, clock.instant(), null, "DEVICE-BBBBBBBB").state());
        m.activate(issued.licenceKey(), "DEVICE-BBBBBBBB");
        m.activate(issued.licenceKey(), "DEVICE-AAAAAAAA"); // re-activating the same device is free
        var ex = assertThrows(LicenceManager.LicenceException.class, () -> m.activate(issued.licenceKey(), "DEVICE-CCCCCCCC"));
        assertEquals(409, ex.httpStatus);
        m.deactivateDevice(issued.licenceId(), "DEVICE-AAAAAAAA");
        m.activate(issued.licenceKey(), "DEVICE-CCCCCCCC");
    }

    @Test
    void keysAreNotUniversalAndNotStoredInPlainText() throws Exception {
        Path file = Files.createTempDirectory("lic").resolve("licences.db");
        var m = new LicenceManager(new LicenceStore(file), new LicenceSigner(keys.getPrivate()), clock);
        var a = m.issue("A", Edition.STARTER, LicenceData.Kind.PERPETUAL, 1, 0, 0);
        var b = m.issue("B", Edition.STARTER, LicenceData.Kind.PERPETUAL, 1, 0, 0);
        assertNotEquals(a.licenceKey(), b.licenceKey());
        assertFalse(Files.readString(file).contains(a.licenceKey().replace("-", "")), "key must be stored hashed only");
        assertEquals(404, assertThrows(LicenceManager.LicenceException.class, () -> m.activate("AAAAA-AAAAA-AAAAA-AAAAA", "DEVICE-AAAAAAAA")).httpStatus);
        assertEquals(404, assertThrows(LicenceManager.LicenceException.class, () -> m.activate("letmein", "DEVICE-AAAAAAAA")).httpStatus);
        assertEquals(400, assertThrows(LicenceManager.LicenceException.class, () -> m.activate(a.licenceKey(), "x")).httpStatus);
        // reload from disk keeps everything
        var m2 = new LicenceManager(new LicenceStore(file), new LicenceSigner(keys.getPrivate()), clock);
        assertNotNull(m2.activate(a.licenceKey(), "DEVICE-AAAAAAAA"));
    }

    @Test
    void renewalExtendsFromExpiryAndAppPicksItUpViaRefresh() throws Exception {
        var m = manager();
        var i = m.issue("Acme", Edition.STARTER, LicenceData.Kind.MONTHLY, 1, 30, 7);
        String t = m.activate(i.licenceKey(), "DEVICE-AAAAAAAA");
        Instant firstExpiry = verifier.verify(t).expiresAt();
        assertEquals(clock.instant().plus(Duration.ofDays(30)), firstExpiry);
        clock.now = clock.now.plus(Duration.ofDays(35));
        assertEquals(LicenceVerifier.State.IN_GRACE, verifier.evaluate(t, clock.instant(), null, "DEVICE-AAAAAAAA").state());
        m.renew(i.licenceId(), 30);
        String t2 = m.refresh(i.licenceKey(), "DEVICE-AAAAAAAA");
        assertEquals(LicenceVerifier.State.VALID, verifier.evaluate(t2, clock.instant(), null, "DEVICE-AAAAAAAA").state());
        assertEquals(404, assertThrows(LicenceManager.LicenceException.class, () -> m.refresh(i.licenceKey(), "DEVICE-ZZZZZZZZ")).httpStatus, "refresh must not activate new devices");
        var perp = m.issue("P", Edition.STARTER, LicenceData.Kind.PERPETUAL, 1, 0, 0);
        assertThrows(LicenceManager.LicenceException.class, () -> m.renew(perp.licenceId(), 30));
    }

    @Test
    void revokedLicencesCannotActivateOrRefresh() throws Exception {
        var m = manager();
        var i = m.issue("Acme", Edition.ENTERPRISE, LicenceData.Kind.ANNUAL, 3, 365, 14);
        m.activate(i.licenceKey(), "DEVICE-AAAAAAAA");
        m.setRevoked(i.licenceId(), true);
        assertEquals(403, assertThrows(LicenceManager.LicenceException.class, () -> m.refresh(i.licenceKey(), "DEVICE-AAAAAAAA")).httpStatus);
        assertEquals(403, assertThrows(LicenceManager.LicenceException.class, () -> m.activate(i.licenceKey(), "DEVICE-BBBBBBBB")).httpStatus);
        m.setRevoked(i.licenceId(), false);
        m.refresh(i.licenceKey(), "DEVICE-AAAAAAAA");
    }

    @Test
    void invalidIssueRequestsAreRejected() throws Exception {
        var m = manager();
        assertThrows(LicenceManager.LicenceException.class, () -> m.issue(" ", Edition.STARTER, LicenceData.Kind.ANNUAL, 1, 30, 0));
        assertThrows(LicenceManager.LicenceException.class, () -> m.issue("X", Edition.STARTER, LicenceData.Kind.ANNUAL, 0, 30, 0));
        assertThrows(LicenceManager.LicenceException.class, () -> m.issue("X", Edition.STARTER, LicenceData.Kind.ANNUAL, 1, 0, 0));
    }

    @Test
    void httpApiEndToEndWithAdminAuthentication() throws Exception {
        var m = manager();
        String admin = "a-very-long-random-admin-token-1234567890";
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        new LicenceHttpApi(m, admin).register(server);
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            HttpClient http = HttpClient.newHttpClient();
            // no token -> 401
            var denied = http.send(HttpRequest.newBuilder(URI.create(base + "/admin/issue")).POST(HttpRequest.BodyPublishers.ofString("licensee=X")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, denied.statusCode());
            var wrong = http.send(HttpRequest.newBuilder(URI.create(base + "/admin/issue")).header("X-Admin-Token", "nope").POST(HttpRequest.BodyPublishers.ofString("licensee=X")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, wrong.statusCode());
            var ok = http.send(HttpRequest.newBuilder(URI.create(base + "/admin/issue")).header("X-Admin-Token", admin)
                    .POST(HttpRequest.BodyPublishers.ofString("licensee=Acme+Stores&edition=professional&kind=annual&devices=1&days=365&grace=14")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ok.statusCode());
            String key = ok.body().lines().filter(l -> l.startsWith("key=")).findFirst().get().substring(4);
            var act = http.send(HttpRequest.newBuilder(URI.create(base + "/v1/activate")).POST(HttpRequest.BodyPublishers.ofString("key=" + key + "&device=DEVICE-AAAAAAAA")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, act.statusCode());
            assertEquals(LicenceVerifier.State.VALID, verifier.evaluate(act.body().trim(), clock.instant(), null, "DEVICE-AAAAAAAA").state());
            var second = http.send(HttpRequest.newBuilder(URI.create(base + "/v1/activate")).POST(HttpRequest.BodyPublishers.ofString("key=" + key + "&device=DEVICE-BBBBBBBB")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(409, second.statusCode());
            var bad = http.send(HttpRequest.newBuilder(URI.create(base + "/v1/activate")).POST(HttpRequest.BodyPublishers.ofString("key=WRONG&device=DEVICE-AAAAAAAA")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, bad.statusCode());
            var list = http.send(HttpRequest.newBuilder(URI.create(base + "/admin/list")).header("X-Admin-Token", admin).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(list.body().contains("Acme Stores"));
            assertFalse(list.body().contains(key), "the list must never reveal licence keys");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void weakAdminTokenIsRefused() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new LicenceHttpApi(manager(), "short"));
    }
}
