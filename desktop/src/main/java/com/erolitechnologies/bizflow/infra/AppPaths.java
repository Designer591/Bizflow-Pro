package com.erolitechnologies.bizflow.infra;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Per-user data locations. Windows: %APPDATA%\BizFlowPro. Override with -Dbizflow.home=... (tests, portable installs). */
public final class AppPaths {
    private final Path home;

    public AppPaths(Path home) {
        this.home = home;
        try {
            Files.createDirectories(dbDir());
            Files.createDirectories(logDir());
            Files.createDirectories(defaultBackupDir());
            Files.createDirectories(assetsDir());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create data folders under " + home, e);
        }
    }

    public static AppPaths forCurrentUser() {
        String override = System.getProperty("bizflow.home");
        if (override != null && !override.isBlank()) return new AppPaths(Path.of(override));
        String appData = System.getenv("APPDATA");
        Path base = (appData != null && !appData.isBlank())
                ? Path.of(appData, "BizFlowPro")
                : Path.of(System.getProperty("user.home"), ".bizflowpro");
        return new AppPaths(base);
    }

    public Path home() { return home; }
    public Path dbDir() { return home.resolve("data"); }
    public Path dbFile() { return dbDir().resolve("bizflow.db"); }
    public Path logDir() { return home.resolve("logs"); }
    public Path defaultBackupDir() { return home.resolve("backups"); }
    public Path assetsDir() { return home.resolve("assets"); }
    public Path configFile() { return home.resolve("bizflow.properties"); }
}
