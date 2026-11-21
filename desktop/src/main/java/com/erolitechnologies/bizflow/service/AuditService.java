package com.erolitechnologies.bizflow.service;

import com.erolitechnologies.bizflow.infra.Db;
import com.erolitechnologies.bizflow.security.Session;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Append-only audit trail. Never pass passwords, tokens or licence secrets in details. */
public final class AuditService {
    public record Entry(long id, String when, String username, String action, String entity, String entityId, String details) {}

    private final Db db;

    public AuditService(Db db) { this.db = db; }

    public static void log(Connection c, Session s, String action, String entity, Object entityId, String details) throws SQLException {
        Db.update(c, "INSERT INTO audit_logs(user_id, username, action, entity, entity_id, details) VALUES (?,?,?,?,?,?)",
                s == null ? null : s.userId(), s == null ? null : s.username(), action, entity,
                entityId == null ? null : entityId.toString(), details);
    }

    public void log(Session s, String action, String entity, Object entityId, String details) {
        db.txVoid(c -> { log(c, s, action, entity, entityId, details); return null; });
    }

    public List<Entry> search(Session s, String text, String from, String to, int limit) {
        s.require(com.erolitechnologies.bizflow.security.Permission.AUDIT_VIEW);
        String like = "%" + (text == null ? "" : text.trim()) + "%";
        return db.read(c -> Db.query(c,
                "SELECT id, created_at, COALESCE(username,''), action, COALESCE(entity,''), COALESCE(entity_id,''), COALESCE(details,'') " +
                "FROM audit_logs WHERE (username LIKE ? OR action LIKE ? OR entity LIKE ? OR details LIKE ?) " +
                "AND date(created_at) >= ? AND date(created_at) <= ? ORDER BY id DESC LIMIT " + Math.max(1, Math.min(limit, 5000)),
                rs -> new Entry(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)),
                like, like, like, like, from == null ? "0000-01-01" : from, to == null ? "9999-12-31" : to));
    }
}
