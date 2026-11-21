package com.erolitechnologies.bizflow.service;

import com.erolitechnologies.bizflow.infra.AppException;
import com.erolitechnologies.bizflow.infra.Db;
import com.erolitechnologies.bizflow.security.Role;
import com.erolitechnologies.bizflow.testsupport.TestEnv;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BackupServiceTest {
    private static long scalar(TestEnv e, String sql) { return e.db.read(c -> Db.scalarLong(c, sql)); }

    private BackupService svc(TestEnv e) { return new BackupService(e.db, e.paths, e.settings, new AuditService(e.db)); }

    @Test
    void backupIsValidAndRestoreBringsDataBackWhileKeepingASafetyCopy() throws Exception {
        var e = new TestEnv();
        var backup = svc(e);
        long p = e.product("Keeper", "1", "2", "5");
        Path file = backup.backupNow(e.admin);
        assertTrue(Files.exists(file));
        assertTrue(backup.isValid(file));
        e.products.setArchived(e.admin, p, true);
        e.product("Added after backup", "1", "2", "1");
        assertEquals(2L, scalar(e, "SELECT COUNT(*) FROM products"));
        backup.restore(e.admin, file);
        assertEquals(1L, scalar(e, "SELECT COUNT(*) FROM products"), "post-backup product is gone");
        assertEquals(0L, scalar(e, "SELECT archived FROM products WHERE id=" + p), "archive flag reverted");
        try (var s = Files.list(e.paths.defaultBackupDir())) {
            assertTrue(s.anyMatch(x -> x.getFileName().toString().startsWith(BackupService.SAFETY_PREFIX) && x.toString().endsWith(".db")), "current data must be saved before restore");
        }
        assertTrue(backup.isValid(file), "the backup used for the restore is untouched");
    }

    @Test
    void corruptAndForeignFilesAreRejectedAndLiveDataIsLeftAlone() throws Exception {
        var e = new TestEnv();
        var backup = svc(e);
        e.product("Live", "1", "2", "1");
        Path junk = e.dir.resolve("junk.db");
        Files.write(junk, new byte[8192]);
        assertThrows(AppException.class, () -> backup.restore(e.admin, junk));
        Path tiny = e.dir.resolve("tiny.db");
        Files.writeString(tiny, "hello");
        assertThrows(AppException.class, () -> backup.restore(e.admin, tiny));
        Path good = backup.backupNow(e.admin);
        byte[] bytes = Files.readAllBytes(good);
        bytes[bytes.length / 2] ^= 0x5A;
        Path damaged = e.dir.resolve("damaged.db");
        Files.write(damaged, bytes);
        Files.copy(good.resolveSibling(good.getFileName() + ".sha256"), e.dir.resolve("damaged.db.sha256"));
        assertThrows(AppException.class, () -> backup.restore(e.admin, damaged), "checksum mismatch");
        assertEquals(1L, scalar(e, "SELECT COUNT(*) FROM products"));
    }

    @Test
    void retentionKeepsNewestAndNeverDeletesSafetyCopies() throws Exception {
        var e = new TestEnv();
        var backup = svc(e);
        e.settings.update(e.admin, Map.of("backup_retention", "2"));
        for (int i = 0; i < 4; i++) { backup.backupNow(e.admin); Thread.sleep(1100); }
        try (var s = Files.list(e.paths.defaultBackupDir())) {
            assertEquals(2L, s.filter(x -> x.getFileName().toString().startsWith(BackupService.PREFIX) && x.toString().endsWith(".db")).count());
        }
    }

    @Test
    void missingExternalDriveFallsBackToDefaultFolderAndReportsIt() {
        var e = new TestEnv();
        var backup = svc(e);
        String bogus = e.dir.resolve("no-such-drive").resolve("x").toString();
        // a path under a regular file cannot be created
        try { Files.writeString(e.dir.resolve("blocker"), "x"); } catch (Exception ex) { throw new RuntimeException(ex); }
        e.settings.update(e.admin, Map.of("backup_dir", e.dir.resolve("blocker").resolve("drive").toString()));
        Path f = backup.backupNow(e.admin);
        assertTrue(f.startsWith(e.paths.defaultBackupDir()));
        assertTrue(backup.status().lastError().contains("not available"));
        assertFalse(backup.status().lastOk().isBlank());
    }

    @Test
    void permissionAndStatusAndSchedule() {
        var e = new TestEnv();
        var backup = svc(e);
        var cashier = e.userWithRole("cash", Role.CASHIER);
        assertThrows(AppException.class, () -> backup.backupNow(cashier));
        assertTrue(backup.status().overdue(), "never backed up -> overdue indicator");
        assertTrue(backup.runIfDue());
        assertFalse(backup.runIfDue(), "not due again straight away");
        assertFalse(backup.status().overdue());
        assertEquals(1, backup.list(e.admin).size());
    }
}
