package com.erolitechnologies.bizflow.licensing;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The signed content of a licence. Serialised as canonical "key=value" lines (no JSON library needed,
 * no deserialisation of objects, so no insecure-deserialisation surface).
 */
public record LicenceData(
        String licenceId,
        Edition edition,
        Kind kind,
        String licensee,
        int maxDevices,
        Instant issuedAt,
        Instant expiresAt,   // null for perpetual
        int graceDays,
        String deviceId) {   // "*" = not bound to a device

    public enum Kind { TRIAL, PERPETUAL, ANNUAL, MONTHLY }

    public LicenceData {
        if (licenceId == null || licenceId.isBlank()) throw new IllegalArgumentException("licenceId required");
        if (licensee == null || licensee.isBlank()) throw new IllegalArgumentException("licensee required");
        if (maxDevices < 1) throw new IllegalArgumentException("maxDevices must be >= 1");
        if (graceDays < 0) throw new IllegalArgumentException("graceDays must be >= 0");
        if (deviceId == null || deviceId.isBlank()) deviceId = "*";
        if (kind != Kind.PERPETUAL && expiresAt == null) throw new IllegalArgumentException("non-perpetual licences need an expiry");
    }

    public byte[] canonicalBytes() {
        StringBuilder sb = new StringBuilder();
        sb.append("v=1\n");
        sb.append("id=").append(clean(licenceId)).append('\n');
        sb.append("edition=").append(edition).append('\n');
        sb.append("kind=").append(kind).append('\n');
        sb.append("licensee=").append(clean(licensee)).append('\n');
        sb.append("devices=").append(maxDevices).append('\n');
        sb.append("issued=").append(issuedAt.getEpochSecond()).append('\n');
        sb.append("expires=").append(expiresAt == null ? "never" : String.valueOf(expiresAt.getEpochSecond())).append('\n');
        sb.append("grace=").append(graceDays).append('\n');
        sb.append("device=").append(clean(deviceId)).append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static LicenceData parse(byte[] bytes) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
            int i = line.indexOf('=');
            if (i > 0) m.put(line.substring(0, i), line.substring(i + 1));
        }
        if (!"1".equals(m.get("v"))) throw new IllegalArgumentException("Unsupported licence version.");
        String exp = m.get("expires");
        return new LicenceData(
                req(m, "id"), Edition.valueOf(req(m, "edition")), Kind.valueOf(req(m, "kind")),
                req(m, "licensee"), Integer.parseInt(req(m, "devices")),
                Instant.ofEpochSecond(Long.parseLong(req(m, "issued"))),
                "never".equals(exp) ? null : Instant.ofEpochSecond(Long.parseLong(exp)),
                Integer.parseInt(req(m, "grace")), req(m, "device"));
    }

    private static String req(Map<String, String> m, String k) {
        String v = m.get(k);
        if (v == null) throw new IllegalArgumentException("Licence is missing field: " + k);
        return v;
    }

    private static String clean(String s) { return s.replace("\n", " ").replace("\r", " "); }
}
