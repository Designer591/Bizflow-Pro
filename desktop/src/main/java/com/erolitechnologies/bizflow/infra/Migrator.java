package com.erolitechnologies.bizflow.infra;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Applies db/migration/V{n}__{name}.sql files (listed in db/migration/index.txt) exactly once, in order.
 * Each migration runs in its own transaction and is recorded in schema_info.
 */
public final class Migrator {
    private final Db db;
    private final String basePath;

    public Migrator(Db db) { this(db, "/db/migration/"); }

    public Migrator(Db db, String basePath) { this.db = db; this.basePath = basePath; }

    public int migrate() {
        List<String> files = readIndex();
        try (Connection c = db.open(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS schema_info (version INTEGER PRIMARY KEY, description TEXT NOT NULL, applied_at TEXT NOT NULL DEFAULT (datetime('now','localtime')))");
        } catch (SQLException e) {
            throw Db.translate(e);
        }
        Set<Long> applied = db.read(c -> new TreeSet<>(Db.query(c, "SELECT version FROM schema_info", rs -> rs.getLong(1))));
        int count = 0;
        for (String f : files) {
            long version = Long.parseLong(f.substring(1, f.indexOf("__")));
            if (applied.contains(version)) continue;
            String sql = load(f);
            String desc = f.substring(f.indexOf("__") + 2, f.length() - 4).replace('_', ' ');
            db.txVoid(c -> {
                for (String stmt : split(sql)) {
                    try (Statement st = c.createStatement()) { st.execute(stmt); }
                }
                Db.update(c, "INSERT INTO schema_info(version, description) VALUES (?,?)", version, desc);
                return null;
            });
            Log.info("Applied migration " + f);
            count++;
        }
        return count;
    }

    private List<String> readIndex() {
        List<String> out = new ArrayList<>();
        try (InputStream in = Migrator.class.getResourceAsStream(basePath + "index.txt")) {
            if (in == null) throw new IllegalStateException("Migration index not found: " + basePath + "index.txt");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) out.add(line);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read migration index", e);
        }
        return out;
    }

    private String load(String file) {
        try (InputStream in = Migrator.class.getResourceAsStream(basePath + file)) {
            if (in == null) throw new IllegalStateException("Missing migration file " + file);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read migration " + file, e);
        }
    }

    /** Splits a script into statements at semicolons that end a line, dropping -- comments. */
    static List<String> split(String script) {
        StringBuilder cleaned = new StringBuilder();
        for (String line : script.split("\n")) {
            int i = line.indexOf("--");
            cleaned.append(i >= 0 ? line.substring(0, i) : line).append('\n');
        }
        List<String> out = new ArrayList<>();
        for (String s : cleaned.toString().split(";\\s*\n")) {
            String t = s.trim();
            if (t.endsWith(";")) t = t.substring(0, t.length() - 1).trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
