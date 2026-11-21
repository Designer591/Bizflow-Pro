package com.erolitechnologies.bizflow.service;

import com.erolitechnologies.bizflow.infra.AppException;
import com.erolitechnologies.bizflow.infra.Db;
import com.erolitechnologies.bizflow.licensing.Edition;
import com.erolitechnologies.bizflow.licensing.LicenceData;
import com.erolitechnologies.bizflow.licensing.LicenceVerifier;
import com.erolitechnologies.bizflow.security.Permission;
import com.erolitechnologies.bizflow.security.Session;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Enumeration;
import java.util.HexFormat;

/**
 * Desktop-side licensing. Verifies signed licences OFFLINE with the bundled PUBLIC key; the private key never exists here.
 * Without an installed licence the app runs a {@value #EVALUATION_DAYS}-day evaluation (Professional features), counted
 * from the day the business was set up. There is no master/universal code anywhere in the application.
 */
public final class LicenceService {
    public static final int EVALUATION_DAYS = 14;

    public record Status(LicenceVerifier.State state, Edition edition, LicenceData.Kind kind, String licensee, String message,
                         boolean evaluation, boolean allowsUse, Instant expiresAt, long daysLeft) {}

    private final Db db;
    private final SettingsService settings;
    private final LicenceVerifier verifier;   // null if this build has no public key
    private final String deviceId;
    private final Clock clock;

    public LicenceService(Db db, SettingsService settings, LicenceVerifier verifier, String deviceId, Clock clock) {
        this.db = db; this.settings = settings; this.verifier = verifier; this.deviceId = deviceId; this.clock = clock;
    }

    public String deviceId() { return deviceId; }

    /** Stable, non-reversible machine id (hash of host name, OS, user and the first hardware address). Contains no personal data. */
    public static String computeDeviceId() {
        try {
            StringBuilder sb = new StringBuilder(System.getProperty("os.name", "")).append('|').append(System.getProperty("user.name", ""))
                    .append('|').append(java.net.InetAddress.getLocalHost().getHostName());
            Enumeration<java.net.NetworkInterface> nis = java.net.NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                byte[] mac = nis.nextElement().getHardwareAddress();
                if (mac != null && mac.length > 0) { sb.append('|').append(HexFormat.of().formatHex(mac)); break; }
            }
            byte[] h = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return "BFP-" + HexFormat.of().formatHex(h).substring(0, 20).toUpperCase();
        } catch (Exception e) {
            return "BFP-UNKNOWN0000";
        }
    }

    public Status status() {
        String token = db.read(c -> Db.queryOne(c, "SELECT token FROM licences ORDER BY id DESC LIMIT 1", rs -> rs.getString(1)));
        Instant now = clock.instant();
        String lastSeenText = settings.get("licence_last_seen");
        Instant lastSeen = lastSeenText.isBlank() ? null : Instant.parse(lastSeenText);
        Status result;
        if (token != null && verifier != null) {
            LicenceVerifier.Status st = verifier.evaluate(token, now, lastSeen, deviceId);
            LicenceData d = st.data();
            long days = d == null || d.expiresAt() == null ? Long.MAX_VALUE : Duration.between(now, d.expiresAt()).toDays();
            result = new Status(st.state(), d == null ? null : d.edition(), d == null ? null : d.kind(), d == null ? null : d.licensee(), st.message(), false,
                    st.allowsUse(), d == null ? null : d.expiresAt(), days);
        } else {
            result = evaluationStatus(now, lastSeen);
        }
        if (result.state() != LicenceVerifier.State.CLOCK_TAMPERED && (lastSeen == null || now.isAfter(lastSeen))) settings.putInternal("licence_last_seen", now.toString());
        return result;
    }

    private Status evaluationStatus(Instant now, Instant lastSeen) {
        String created = db.read(c -> Db.queryOne(c, "SELECT created_at FROM businesses ORDER BY id LIMIT 1", rs -> rs.getString(1)));
        Instant start = created == null ? now : java.time.LocalDateTime.parse(created.replace(' ', 'T')).atZone(java.time.ZoneId.systemDefault()).toInstant();
        Instant end = start.plus(Duration.ofDays(EVALUATION_DAYS));
        if (lastSeen != null && now.isBefore(lastSeen.minus(Duration.ofHours(24))))
            return new Status(LicenceVerifier.State.CLOCK_TAMPERED, Edition.PROFESSIONAL, LicenceData.Kind.TRIAL, "Evaluation", "The system clock appears to have been set back.", true, false, end, 0);
        if (now.isBefore(end)) {
            long left = Math.max(0, Duration.between(now, end).toDays());
            return new Status(LicenceVerifier.State.VALID, Edition.PROFESSIONAL, LicenceData.Kind.TRIAL, "Evaluation",
                    "Evaluation period: " + left + " day(s) left. Activate a licence to continue.", true, true, end, left);
        }
        return new Status(LicenceVerifier.State.EXPIRED, Edition.PROFESSIONAL, LicenceData.Kind.TRIAL, "Evaluation",
                "The evaluation period has ended. Enter a licence key to continue.", true, false, end, 0);
    }

    public boolean hasFeature(Edition.Feature f) {
        Status s = status();
        return s.allowsUse() && s.edition() != null && s.edition().includes(f);
    }

    public void requireFeature(Edition.Feature f) {
        Status s = status();
        if (!s.allowsUse()) throw new AppException(s.message());
        if (s.edition() == null || !s.edition().includes(f))
            throw new AppException("This feature is not included in your " + (s.edition() == null ? "" : s.edition().name().toLowerCase()) + " edition. Upgrade your licence to use it.");
    }

    /** Install a signed licence token (online activation result, or pasted/loaded for offline activation). */
    public Status install(Session s, String token) {
        s.require(Permission.LICENCE_MANAGE);
        if (verifier == null) throw new AppException("This build does not contain a licence verification key. Contact EROLITECHNOLOGIES.");
        LicenceVerifier.Status st = verifier.evaluate(token == null ? "" : token.trim(), clock.instant(), null, deviceId);
        if (st.state() == LicenceVerifier.State.INVALID) throw new AppException("That licence is not valid: " + st.message());
        if (st.state() == LicenceVerifier.State.DEVICE_MISMATCH) throw new AppException("That licence was issued for a different computer. Your device ID is " + deviceId + ".");
        if (st.state() == LicenceVerifier.State.EXPIRED) throw new AppException("That licence has expired. Renew it first.");
        LicenceData d = st.data();
        db.txVoid(c -> {
            Db.update(c, "DELETE FROM licence_activations");
            Db.update(c, "DELETE FROM licences");
            Db.update(c, "INSERT INTO licences(licence_id, edition, kind, licensee, max_devices, issued_at, expires_at, grace_days, token) VALUES (?,?,?,?,?,?,?,?,?)",
                    d.licenceId(), d.edition().name(), d.kind().name(), d.licensee(), d.maxDevices(), d.issuedAt().toString(), d.expiresAt() == null ? null : d.expiresAt().toString(), d.graceDays(), token.trim());
            Db.update(c, "INSERT INTO licence_activations(licence_id, device_id, last_check_at) VALUES (?,?, datetime('now','localtime'))", d.licenceId(), deviceId);
            AuditService.log(c, s, "LICENCE_INSTALLED", "licence", d.licenceId(), d.edition() + " " + d.kind() + " for " + d.licensee());
            return null;
        });
        return status();
    }

    /** Contact the licence server to activate this device. Key is kept only to refresh later; never logged. */
    public Status activateOnline(Session s, String licenceKey, String serverUrl) {
        s.require(Permission.LICENCE_MANAGE);
        if (licenceKey == null || licenceKey.isBlank()) throw new AppException("Enter your licence key.");
        String token = post(serverUrl, "/v1/activate", licenceKey, "key=" + enc(licenceKey.trim()) + "&device=" + enc(deviceId));
        Status st = install(s, token);
        settings.putInternal("licence_server_url", serverUrl.trim());
        settings.putInternal("licence_key_hint", licenceKey.trim().substring(Math.max(0, licenceKey.trim().length() - 4)));
        return st;
    }

    /** Pick up renewals/upgrades from the server. */
    public Status refreshOnline(Session s, String licenceKey) {
        s.require(Permission.LICENCE_MANAGE);
        String url = settings.get("licence_server_url");
        if (url.isBlank()) throw new AppException("No licence server is configured. Activate online first, or install a renewed licence file.");
        return install(s, post(url, "/v1/refresh", licenceKey, "key=" + enc(licenceKey.trim()) + "&device=" + enc(deviceId)));
    }

    private String post(String serverUrl, String path, String key, String body) {
        URI uri;
        try { uri = URI.create(serverUrl.trim().replaceAll("/+$", "") + path); } catch (RuntimeException e) { throw new AppException("The licence server address is not valid."); }
        boolean local = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !local) throw new AppException("The licence server address must start with https://");
        try {
            HttpResponse<String> r = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build().send(
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200) return r.body().trim();
            String msg = r.body().lines().filter(l -> l.startsWith("error=")).map(l -> l.substring(6)).findFirst().orElse("The licence server refused the request.");
            throw new AppException(msg);
        } catch (java.io.IOException e) {
            throw new AppException("Could not reach the licence server. Check your internet connection, or use offline activation (see the licensing guide).", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException("The activation was interrupted.");
        }
    }

    private static String enc(String v) { return URLEncoder.encode(v, StandardCharsets.UTF_8); }
}
