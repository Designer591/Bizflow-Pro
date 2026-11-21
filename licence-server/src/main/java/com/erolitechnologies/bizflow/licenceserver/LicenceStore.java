package com.erolitechnologies.bizflow.licenceserver;

import com.erolitechnologies.bizflow.licensing.Edition;
import com.erolitechnologies.bizflow.licensing.LicenceData;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Small file-backed store (atomic rewrite). Only a SHA-256 HASH of each licence key is stored, never the key itself. */
public final class LicenceStore {
    public static final class Record {
        public String licenceId, keyHash, licensee;
        public Edition edition;
        public LicenceData.Kind kind;
        public int maxDevices, graceDays;
        public Instant issuedAt, expiresAt;      // expiresAt null = perpetual
        public boolean revoked;
        public final Set<String> devices = new LinkedHashSet<>();
    }

    private final Path file;
    private final Map<String, Record> byId = new LinkedHashMap<>();

    public LicenceStore(Path file) {
        this.file = file;
        load();
    }

    public synchronized Collection<Record> all() { return new ArrayList<>(byId.values()); }
    public synchronized Record byId(String id) { return byId.get(id); }

    public synchronized Record byKeyHash(String hash) {
        for (Record r : byId.values()) if (MessageDigestUtil.constantTimeEquals(r.keyHash, hash)) return r;
        return null;
    }

    public synchronized void put(Record r) { byId.put(r.licenceId, r); save(); }
    public synchronized void saveNow() { save(); }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            Record cur = null;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.equals("[licence]")) { cur = new Record(); continue; }
                if (line.equals("[end]") && cur != null) { byId.put(cur.licenceId, cur); cur = null; continue; }
                int i = line.indexOf('=');
                if (cur == null || i < 0) continue;
                String k = line.substring(0, i), v = line.substring(i + 1);
                switch (k) {
                    case "id" -> cur.licenceId = v;
                    case "keyHash" -> cur.keyHash = v;
                    case "licensee" -> cur.licensee = v;
                    case "edition" -> cur.edition = Edition.valueOf(v);
                    case "kind" -> cur.kind = LicenceData.Kind.valueOf(v);
                    case "maxDevices" -> cur.maxDevices = Integer.parseInt(v);
                    case "grace" -> cur.graceDays = Integer.parseInt(v);
                    case "issued" -> cur.issuedAt = Instant.ofEpochSecond(Long.parseLong(v));
                    case "expires" -> cur.expiresAt = v.equals("never") ? null : Instant.ofEpochSecond(Long.parseLong(v));
                    case "revoked" -> cur.revoked = Boolean.parseBoolean(v);
                    case "device" -> cur.devices.add(v);
                    default -> { }
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Licence database is unreadable: " + file, e);
        }
    }

    private void save() {
        StringBuilder sb = new StringBuilder();
        for (Record r : byId.values()) {
            sb.append("[licence]\nid=").append(r.licenceId).append("\nkeyHash=").append(r.keyHash).append("\nlicensee=").append(r.licensee.replace('\n', ' '))
              .append("\nedition=").append(r.edition).append("\nkind=").append(r.kind).append("\nmaxDevices=").append(r.maxDevices)
              .append("\ngrace=").append(r.graceDays).append("\nissued=").append(r.issuedAt.getEpochSecond())
              .append("\nexpires=").append(r.expiresAt == null ? "never" : String.valueOf(r.expiresAt.getEpochSecond()))
              .append("\nrevoked=").append(r.revoked).append('\n');
            for (String d : r.devices) sb.append("device=").append(d).append('\n');
            sb.append("[end]\n");
        }
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Could not save the licence database", e);
        }
    }
}
