package com.erolitechnologies.bizflow.licensing;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/** Desktop-side offline verification. Holds ONLY the public key. */
public final class LicenceVerifier {
    private final PublicKey publicKey;

    public LicenceVerifier(PublicKey publicKey) { this.publicKey = publicKey; }

    /** Load the public key shipped with the app (X.509 / base64 DER). */
    public static LicenceVerifier fromBase64PublicKey(String b64) {
        try {
            byte[] der = Base64.getMimeDecoder().decode(b64.trim());
            return new LicenceVerifier(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid licence public key", e);
        }
    }

    public enum State { VALID, EXPIRING_SOON, IN_GRACE, EXPIRED, INVALID, DEVICE_MISMATCH, CLOCK_TAMPERED }

    public record Status(State state, LicenceData data, String message) {
        /** Whether the app may be used normally (grace period still allows use, with a warning). */
        public boolean allowsUse() {
            return state == State.VALID || state == State.EXPIRING_SOON || state == State.IN_GRACE;
        }
    }

    /** Verify signature and parse. Throws IllegalArgumentException for any tampered/malformed token. */
    public LicenceData verify(String token) {
        try {
            if (token == null) throw new IllegalArgumentException("No licence provided.");
            String[] parts = token.trim().split("\\.");
            if (parts.length != 2) throw new IllegalArgumentException("Malformed licence.");
            Base64.Decoder dec = Base64.getUrlDecoder();
            byte[] payload = dec.decode(parts[0]);
            byte[] sig = dec.decode(parts[1]);
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(publicKey);
            s.update(payload);
            if (!s.verify(sig)) throw new IllegalArgumentException("Licence signature is not valid.");
            return LicenceData.parse(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Licence could not be verified.", e);
        } catch (RuntimeException e) {
            if (e instanceof IllegalArgumentException) throw e;
            throw new IllegalArgumentException("Licence is corrupted.", e);
        }
    }

    /**
     * @param lastSeenAt latest time the app has previously observed (persisted); guards against rolling the clock back
     * @param deviceId this machine's id; a licence bound to another device is rejected (the activation LIMIT itself is enforced by the licence server)
     */
    public Status evaluate(String token, Instant now, Instant lastSeenAt, String deviceId) {
        LicenceData d;
        try {
            d = verify(token);
        } catch (IllegalArgumentException e) {
            return new Status(State.INVALID, null, e.getMessage());
        }
        if (lastSeenAt != null && now.isBefore(lastSeenAt.minus(Duration.ofHours(24))))
            return new Status(State.CLOCK_TAMPERED, d, "The system clock appears to have been set back. Correct the date and time.");
        if (!"*".equals(d.deviceId()) && !d.deviceId().equals(deviceId))
            return new Status(State.DEVICE_MISMATCH, d, "This licence was activated for a different computer.");
        if (d.expiresAt() == null) return new Status(State.VALID, d, "Perpetual licence.");
        if (now.isBefore(d.expiresAt())) {
            long days = Duration.between(now, d.expiresAt()).toDays();
            if (days <= 14) return new Status(State.EXPIRING_SOON, d, "Licence expires in " + days + " day(s). Please renew.");
            return new Status(State.VALID, d, "Licence valid until " + d.expiresAt() + ".");
        }
        Instant graceEnd = d.expiresAt().plus(Duration.ofDays(d.graceDays()));
        if (now.isBefore(graceEnd)) {
            long left = Duration.between(now, graceEnd).toDays();
            return new Status(State.IN_GRACE, d, "Licence expired. Grace period ends in " + left + " day(s). Renew now.");
        }
        return new Status(State.EXPIRED, d, "Licence expired. Please renew to continue.");
    }
}
