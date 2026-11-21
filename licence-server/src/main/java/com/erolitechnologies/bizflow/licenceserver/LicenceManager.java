package com.erolitechnologies.bizflow.licenceserver;

import com.erolitechnologies.bizflow.licensing.Edition;
import com.erolitechnologies.bizflow.licensing.LicenceData;
import com.erolitechnologies.bizflow.licensing.LicenceSigner;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Locale;
import java.util.UUID;

/** Issuing, activating, renewing and revoking licences. Key format: XXXXX-XXXXX-XXXXX-XXXXX (random, single customer, hashed at rest). */
public final class LicenceManager {
    public static final class LicenceException extends RuntimeException {
        public final int httpStatus;
        public LicenceException(int httpStatus, String message) { super(message); this.httpStatus = httpStatus; }
    }

    public record Issued(String licenceId, String licenceKey) {}

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final LicenceStore store;
    private final LicenceSigner signer;
    private final java.time.Clock clock;

    public LicenceManager(LicenceStore store, LicenceSigner signer, java.time.Clock clock) {
        this.store = store; this.signer = signer; this.clock = clock;
    }

    public Issued issue(String licensee, Edition edition, LicenceData.Kind kind, int maxDevices, int validDays, int graceDays) {
        if (licensee == null || licensee.isBlank()) throw new LicenceException(400, "Licensee is required.");
        if (maxDevices < 1 || maxDevices > 1000) throw new LicenceException(400, "Device limit must be between 1 and 1000.");
        if (kind != LicenceData.Kind.PERPETUAL && validDays < 1) throw new LicenceException(400, "Validity (days) is required for non-perpetual licences.");
        String key = newKey();
        LicenceStore.Record r = new LicenceStore.Record();
        r.licenceId = "LIC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        r.keyHash = MessageDigestUtil.sha256(normalise(key));
        r.licensee = licensee.trim();
        r.edition = edition; r.kind = kind; r.maxDevices = maxDevices; r.graceDays = Math.max(0, graceDays);
        r.issuedAt = clock.instant();
        r.expiresAt = kind == LicenceData.Kind.PERPETUAL ? null : r.issuedAt.plus(Duration.ofDays(validDays));
        store.put(r);
        return new Issued(r.licenceId, key);
    }

    /** Activates (or re-confirms) a device and returns a signed, device-bound licence token. */
    public String activate(String licenceKey, String deviceId) {
        LicenceStore.Record r = lookup(licenceKey);
        if (deviceId == null || !deviceId.matches("[A-Za-z0-9\\-]{8,80}")) throw new LicenceException(400, "Invalid device identifier.");
        if (r.revoked) throw new LicenceException(403, "This licence has been revoked. Contact EROLITECHNOLOGIES.");
        synchronized (store) {
            if (!r.devices.contains(deviceId)) {
                if (r.devices.size() >= r.maxDevices)
                    throw new LicenceException(409, "This licence is already activated on the maximum of " + r.maxDevices + " device(s). Deactivate one first.");
                r.devices.add(deviceId);
                store.saveNow();
            }
        }
        return tokenFor(r, deviceId);
    }

    /** Same checks as activate, but never adds a new device: used by the app to pick up renewals. */
    public String refresh(String licenceKey, String deviceId) {
        LicenceStore.Record r = lookup(licenceKey);
        if (r.revoked) throw new LicenceException(403, "This licence has been revoked. Contact EROLITECHNOLOGIES.");
        if (!r.devices.contains(deviceId)) throw new LicenceException(404, "This device is not activated for the licence.");
        return tokenFor(r, deviceId);
    }

    public void deactivateDevice(String licenceId, String deviceId) {
        LicenceStore.Record r = require(licenceId);
        r.devices.remove(deviceId);
        store.saveNow();
    }

    /** Extends from the later of now or the current expiry, so renewing early never loses paid time. */
    public Instant renew(String licenceId, int addDays) {
        LicenceStore.Record r = require(licenceId);
        if (r.kind == LicenceData.Kind.PERPETUAL) throw new LicenceException(400, "Perpetual licences do not need renewal.");
        if (addDays < 1) throw new LicenceException(400, "Renewal period must be at least 1 day.");
        Instant base = r.expiresAt != null && r.expiresAt.isAfter(clock.instant()) ? r.expiresAt : clock.instant();
        r.expiresAt = base.plus(Duration.ofDays(addDays));
        store.saveNow();
        return r.expiresAt;
    }

    public void setRevoked(String licenceId, boolean revoked) {
        require(licenceId).revoked = revoked;
        store.saveNow();
    }

    public Collection<LicenceStore.Record> list() { return store.all(); }

    private String tokenFor(LicenceStore.Record r, String deviceId) {
        return signer.issue(new LicenceData(r.licenceId, r.edition, r.kind, r.licensee, r.maxDevices, r.issuedAt, r.expiresAt, r.graceDays, deviceId));
    }

    private LicenceStore.Record lookup(String key) {
        if (key == null) throw new LicenceException(404, "Licence key not recognised.");
        LicenceStore.Record r = store.byKeyHash(MessageDigestUtil.sha256(normalise(key)));
        if (r == null) throw new LicenceException(404, "Licence key not recognised.");
        return r;
    }

    private LicenceStore.Record require(String id) {
        LicenceStore.Record r = store.byId(id);
        if (r == null) throw new LicenceException(404, "Licence not found.");
        return r;
    }

    static String normalise(String key) { return key.replace("-", "").replace(" ", "").trim().toUpperCase(Locale.ROOT); }

    private static String newKey() {
        StringBuilder sb = new StringBuilder();
        for (int g = 0; g < 4; g++) {
            if (g > 0) sb.append('-');
            for (int i = 0; i < 5; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
