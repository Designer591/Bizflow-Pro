package com.erolitechnologies.bizflow.infra;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Database access. One connection per unit of work; tx() wraps work in a single transaction that
 * rolls back on any exception. SQLite connections use foreign keys, WAL and IMMEDIATE transactions
 * so concurrent writers queue instead of failing half-way.
 */
public final class Db {
    public interface ConnectionSource { Connection get() throws SQLException; }
    public interface Work<T> { T run(Connection c) throws SQLException; }
    public interface RowMapper<T> { T map(ResultSet rs) throws SQLException; }

    private final ConnectionSource source;
    private final String description;

    public Db(ConnectionSource source, String description) {
        this.source = source;
        this.description = description;
    }

    public static Db sqlite(java.nio.file.Path file) {
        String url = "jdbc:sqlite:" + file.toAbsolutePath();
        return new Db(() -> {
            Properties p = new Properties();
            p.setProperty("foreign_keys", "true");
            p.setProperty("busy_timeout", "10000");
            p.setProperty("transaction_mode", "IMMEDIATE");
            Connection c = DriverManager.getConnection(url, p);
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
            }
            return c;
        }, "SQLite " + file.getFileName());
    }

    public String description() { return description; }

    public Connection open() throws SQLException { return source.get(); }

    /** Run inside one transaction; commit on success, roll back on any failure. */
    public <T> T tx(Work<T> work) {
        Connection c = null;
        try {
            c = source.get();
            c.setAutoCommit(false);
            T result = work.run(c);
            c.commit();
            return result;
        } catch (RuntimeException e) {
            rollbackQuietly(c);
            throw e;
        } catch (SQLException e) {
            rollbackQuietly(c);
            throw translate(e);
        } finally {
            closeQuietly(c);
        }
    }

    public void txVoid(Work<Void> work) { tx(work); }

    /** Read-only convenience (still a short-lived connection). */
    public <T> T read(Work<T> work) {
        Connection c = null;
        try {
            c = source.get();
            return work.run(c);
        } catch (SQLException e) {
            throw translate(e);
        } finally {
            closeQuietly(c);
        }
    }

    private static void rollbackQuietly(Connection c) {
        if (c == null) return;
        try { c.rollback(); } catch (SQLException e) { Log.warn("Rollback failed: " + e.getMessage()); }
    }

    private static void closeQuietly(Connection c) {
        if (c == null) return;
        try { c.close(); } catch (SQLException ignored) { }
    }

    public static DbException translate(SQLException e) {
        Log.error("Database error", e);
        String m = String.valueOf(e.getMessage()).toLowerCase();
        if (m.contains("unique")) return new DbException("That record already exists (a value that must be unique is duplicated).", e);
        if (m.contains("foreign key")) return new DbException("This record is linked to other records and cannot be changed that way.", e);
        if (m.contains("locked") || m.contains("busy")) return new DbException("The database is busy. Please try again in a moment.", e);
        return new DbException("A database problem occurred. The details were saved to the application log.", e);
    }

    // ---------- small JDBC helpers (always prepared statements) ----------

    public static <T> List<T> query(Connection c, String sql, RowMapper<T> mapper, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) out.add(mapper.map(rs));
                return out;
            }
        }
    }

    public static <T> T queryOne(Connection c, String sql, RowMapper<T> mapper, Object... args) throws SQLException {
        List<T> l = query(c, sql, mapper, args);
        return l.isEmpty() ? null : l.get(0);
    }

    public static long scalarLong(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    public static int update(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
        }
    }

    /** Insert and return the generated row id. */
    public static long insert(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, args);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
            throw new SQLException("No generated key returned");
        }
    }

    private static void bind(PreparedStatement ps, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a == null) ps.setObject(i + 1, null);
            else if (a instanceof Boolean b) ps.setInt(i + 1, b ? 1 : 0);
            else if (a instanceof Long l) ps.setLong(i + 1, l);
            else if (a instanceof Integer n) ps.setInt(i + 1, n);
            else ps.setString(i + 1, a.toString());
        }
    }
}
