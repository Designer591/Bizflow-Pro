package com.erolitechnologies.bizflow.service;

import com.erolitechnologies.bizflow.infra.AppException;
import com.erolitechnologies.bizflow.infra.AppPaths;
import com.erolitechnologies.bizflow.infra.Db;
import com.erolitechnologies.bizflow.infra.Log;
import com.erolitechnologies.bizflow.security.Permission;
import com.erolitechnologies.bizflow.security.Session;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * Consistent online backups (VACUUM INTO produces a complete snapshot even while the app is running), SHA-256 sidecar
 * for corruption detection, validation, retention, and a restore that ALWAYS saves the current database first.
 */
public final class BackupService {
    public static final String PREFIX = "bizflow-backup-";
    public static final String SAFETY_PREFIX = "pre-restore-";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public record BackupFile(Path path, long sizeBytes, String modified, boolean valid, boolean safetyCopy) {}
    public record Status(String lastOk, String lastError, boolean overdue, int intervalHours) {}

    private final Db db;
    private final AppPaths paths;
    private final SettingsService settings;
    private final AuditService audit;

    public BackupService(Db db, AppPaths paths, SettingsService settings, AuditService audit) {
        this.db = db; this.paths = paths; this.settings = settings; this.audit = audit;
    }

    public Path configuredDir() {
        String d = settings.get("backup_dir");
        return d == null || d.isBlank() ? paths.defaultBackupDir() : Path.of(d);
    }

    /** Manual or scheduled backup. session may be null for the scheduler. Returns the file written to the configured folder. */
    public synchronized Path backupNow(Session session) {
        if (session != null) session.require(Permission.BACKUP_MANAGE);
        Path target = configuredDir();
        String warning = null;
        try {
            Files.createDirectories(target);
            if (!Files.isWritable(target)) throw new IOException("not writable");
        } catch (IOException | RuntimeException e) {
            warning = "Backup folder " + target + " is not available (is the external drive connected?). Saved to the default folder instead.";
            target = paths.defaultBackupDir();
            try { Files.createDirectories(target); } catch (IOException ex) { return fail("Cannot create any backup folder.", ex); }
        }
        try {
            Path file = target.resolve(PREFIX + LocalDateTime.now().format(STAMP) + ".db");
            snapshot(file);
            writeChecksum(file);
            validate(file);
            applyRetention(target);
            settings.putInternal("backup_last_ok", LocalDateTime.now().toString());
            settings.putInternal("backup_last_error", warning == null ? "" : warning);
            audit.log(session, "BACKUP_CREATED", "backup", null, file.getFileName().toString());
            Log.info("Backup created: " + file);
            return file;
        } catch (AppException e) {
            return fail(e.getMessage(), e);
        } catch (IOException | RuntimeException e) {
            return fail("The backup could not be completed.", e);
        }
    }

    private Path fail(String message, Exception cause) {
        Log.error("Backup failed: " + message, cause);
        try { settings.putInternal("backup_last_error", LocalDateTime.now().toString().substring(0, 16) + " - " + message); } catch (RuntimeException ignored) { }
        throw new AppException("Backup failed: " + message, cause);
    }

    private void snapshot(Path file) throws IOException {
        Files.deleteIfExists(file);
        String escaped = file.toAbsolutePath().toString().replace("'", "''");
        try {
            db.read(c -> { try (var st = c.createStatement()) { st.execute("VACUUM INTO '" + escaped + "'"); } return null; });
        } catch (RuntimeException e) {
            Files.deleteIfExists(file);
            throw e;
        }
    }

    private static Path sumFile(Path file) { return file.resolveSibling(file.getFileName() + ".sha256"); }

    private static String sha256(Path file) throws IOException {
        try (var in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static void writeChecksum(Path file) throws IOException { Files.writeString(sumFile(file), sha256(file)); }

    /** Throws AppException describing the problem if the file is not a usable BizFlow backup. */
    public static void validate(Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) < 4096) throw new AppException("That file is not a valid BizFlow backup.");
            Path sum = sumFile(file);
            if (Files.exists(sum) && !Files.readString(sum).trim().equals(sha256(file)))
                throw new AppException("This backup file is damaged (its checksum does not match). Use another backup.");
        } catch (IOException e) { throw new AppException("The backup file could not be read.", e); }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath())) {
            String integrity = Db.queryOne(c, "PRAGMA integrity_check", rs -> rs.getString(1));
            if (!"ok".equalsIgnoreCase(integrity))
                throw new AppException("This backup file is corrupted and cannot be used.");
            if (Db.scalarLong(c, "SELECT COUNT(*) FROM sqlite_master WHERE name IN ('schema_info','sales','products','users','businesses')") < 5)
                throw new AppException("That file is not a BizFlow Pro database backup.");
        } catch (SQLException e) {
            throw new AppException("That file is not a valid BizFlow database.", e);
        }
    }

    public boolean isValid(Path file) {
        try { validate(file); return true; } catch (AppException e) { return false; }
    }

    private void applyRetention(Path dir) throws IOException {
        int keep = Math.max(1, settings.getInt("backup_retention"));
        List<Path> files = listBackupPaths(dir);
        for (int i = keep; i < files.size(); i++) {
            Files.deleteIfExists(files.get(i));
            Files.deleteIfExists(sumFile(files.get(i)));
        }
    }

    /** Newest first. Safety copies (pre-restore-*) are never auto-deleted. */
    private static List<Path> listBackupPaths(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return new ArrayList<>(s.filter(p -> p.getFileName().toString().startsWith(PREFIX) && p.getFileName().toString().endsWith(".db"))
                    .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString())).toList());
        }
    }

    public List<BackupFile> list(Session s) {
        s.require(Permission.BACKUP_MANAGE);
        List<BackupFile> out = new ArrayList<>();
        try {
            for (Path dir : new LinkedHashSetOf(configuredDir(), paths.defaultBackupDir()).dirs) {
                if (!Files.isDirectory(dir)) continue;
                try (Stream<Path> st = Files.list(dir)) {
                    for (Path p : st.filter(x -> x.getFileName().toString().endsWith(".db")).sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString())).toList()) {
                        String n = p.getFileName().toString();
                        if (!n.startsWith(PREFIX) && !n.startsWith(SAFETY_PREFIX)) continue;
                        out.add(new BackupFile(p, Files.size(p), Files.getLastModifiedTime(p).toString(), isValid(p), n.startsWith(SAFETY_PREFIX)));
                    }
                }
            }
        } catch (IOException e) { throw new AppException("Could not read the backup folder.", e); }
        return out;
    }

    private record LinkedHashSetOf(List<Path> dirs) {
        LinkedHashSetOf(Path a, Path b) { this(a.equals(b) ? List.of(a) : List.of(a, b)); }
    }

    /**
     * Restore: validate -> save current database as pre-restore-*.db (never overwritten, never auto-deleted) -> swap in.
     * If the swap fails, the original database is put back.
     */
    public synchronized void restore(Session s, Path backup) {
        s.require(Permission.BACKUP_MANAGE);
        validate(backup);
        Path dbFile = paths.dbFile();
        Path safety = paths.defaultBackupDir().resolve(SAFETY_PREFIX + LocalDateTime.now().format(STAMP) + ".db");
        try {
            Files.createDirectories(safety.getParent());
            snapshot(safety);
            writeChecksum(safety);
            audit.log(s, "RESTORE_STARTED", "backup", null, backup.getFileName() + " (current data saved as " + safety.getFileName() + ")");
            Path staged = dbFile.resolveSibling("restore-staging.db");
            Files.copy(backup, staged, StandardCopyOption.REPLACE_EXISTING);
            validate(staged.resolveSibling(staged.getFileName())); // same checks on the staged copy (no sidecar there)
            Path old = dbFile.resolveSibling("replaced-by-restore.db");
            Files.deleteIfExists(Path.of(dbFile + "-wal")); Files.deleteIfExists(Path.of(dbFile + "-shm"));
            Files.move(dbFile, old, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(staged, dbFile, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException | RuntimeException e) {
                Files.move(old, dbFile, StandardCopyOption.REPLACE_EXISTING); // put original back
                throw e;
            }
            Files.deleteIfExists(old);
            Log.info("Database restored from " + backup);
        } catch (AppException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            Log.error("Restore failed", e);
            throw new AppException("The restore failed and your current data was left unchanged.", e);
        }
    }

    public Status status() {
        String ok = settings.get("backup_last_ok");
        String err = settings.get("backup_last_error");
        int hours = Math.max(1, settings.getInt("backup_interval_hours"));
        boolean overdue = settings.getBool("backup_enabled") && (ok.isBlank() || LocalDateTime.parse(ok).isBefore(LocalDateTime.now().minusHours(hours * 2L)));
        return new Status(ok, err, overdue, hours);
    }

    /** Called periodically by the app; performs a backup if the schedule says one is due. */
    public boolean runIfDue() {
        if (!settings.getBool("backup_enabled")) return false;
        String ok = settings.get("backup_last_ok");
        int hours = Math.max(1, settings.getInt("backup_interval_hours"));
        if (!ok.isBlank() && LocalDateTime.parse(ok).isAfter(LocalDateTime.now().minusHours(hours))) return false;
        backupNow(null);
        return true;
    }
}
