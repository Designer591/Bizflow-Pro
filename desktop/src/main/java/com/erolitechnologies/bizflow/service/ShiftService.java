package com.erolitechnologies.bizflow.service;

import com.erolitechnologies.bizflow.domain.Money;
import com.erolitechnologies.bizflow.infra.AppException;
import com.erolitechnologies.bizflow.infra.Db;
import com.erolitechnologies.bizflow.security.Permission;
import com.erolitechnologies.bizflow.security.Session;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Cash drawer shifts: open with a float, close with a count, reconcile against recorded cash movements. */
public final class ShiftService {
    public record Shift(long id, long userId, String cashier, String openedAt, String closedAt, BigDecimal opening, BigDecimal expected,
                        BigDecimal counted, BigDecimal variance, String status, String notes) {}

    private final Db db;

    public ShiftService(Db db) { this.db = db; }

    private static final String SELECT =
            "SELECT s.id, s.user_id, u.full_name, s.opened_at, COALESCE(s.closed_at,''), s.opening_minor, s.expected_minor, s.counted_minor, s.variance_minor, s.status, COALESCE(s.notes,'') " +
            "FROM cash_shifts s JOIN users u ON u.id = s.user_id ";

    private static Shift map(java.sql.ResultSet rs) throws SQLException {
        return new Shift(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), Money.fromMinor(rs.getLong(6)),
                nz(rs, 7), nz(rs, 8), nz(rs, 9), rs.getString(10), rs.getString(11));
    }

    private static BigDecimal nz(java.sql.ResultSet rs, int i) throws SQLException {
        Object o = rs.getObject(i);
        return o == null ? null : Money.fromMinor(((Number) o).longValue());
    }

    public static Long openShiftId(Connection c, long userId) throws SQLException {
        return Db.queryOne(c, "SELECT id FROM cash_shifts WHERE user_id = ? AND status = 'OPEN' ORDER BY id DESC LIMIT 1", rs -> rs.getLong(1), userId);
    }

    public Shift currentShift(Session s) {
        s.require(Permission.SHIFT_OWN);
        return db.read(c -> Db.queryOne(c, SELECT + "WHERE s.user_id = ? AND s.status = 'OPEN' ORDER BY s.id DESC LIMIT 1", ShiftService::map, s.userId()));
    }

    public long open(Session s, BigDecimal openingFloat) {
        s.require(Permission.SHIFT_OWN);
        Validate.nonNegative(openingFloat, "Opening cash");
        return db.tx(c -> {
            if (openShiftId(c, s.userId()) != null) throw new AppException("You already have an open shift. Close it before opening a new one.");
            long id = Db.insert(c, "INSERT INTO cash_shifts(branch_id, user_id, opening_minor) VALUES (?,?,?)", s.branchId(), s.userId(), Money.toMinor(openingFloat));
            AuditService.log(c, s, "SHIFT_OPENED", "shift", id, "float=" + Money.round(openingFloat));
            return id;
        });
    }

    /** Cash the drawer should hold right now: opening float + cash in - cash out for this shift. */
    public static long expectedCashMinor(Connection c, long shiftId) throws SQLException {
        long opening = Db.scalarLong(c, "SELECT opening_minor FROM cash_shifts WHERE id = ?", shiftId);
        long net = Db.scalarLong(c, "SELECT COALESCE(SUM(CASE WHEN direction = 'IN' THEN amount_minor ELSE -amount_minor END),0) FROM payments WHERE shift_id = ? AND method = 'Cash'", shiftId);
        return opening + net;
    }

    public BigDecimal expectedNow(Session s) {
        s.require(Permission.SHIFT_OWN);
        return db.read(c -> {
            Long id = openShiftId(c, s.userId());
            if (id == null) throw new AppException("You have no open shift.");
            return Money.fromMinor(expectedCashMinor(c, id));
        });
    }

    public Shift close(Session s, BigDecimal countedCash, String notes) {
        s.require(Permission.SHIFT_OWN);
        Validate.nonNegative(countedCash, "Counted cash");
        return db.tx(c -> {
            Long id = openShiftId(c, s.userId());
            if (id == null) throw new AppException("You have no open shift.");
            long expected = expectedCashMinor(c, id);
            long counted = Money.toMinor(countedCash);
            Db.update(c, "UPDATE cash_shifts SET status='CLOSED', closed_at=datetime('now','localtime'), expected_minor=?, counted_minor=?, variance_minor=?, notes=? WHERE id=?",
                    expected, counted, counted - expected, Validate.optional(notes), id);
            AuditService.log(c, s, "SHIFT_CLOSED", "shift", id, "expected=" + Money.fromMinor(expected) + ", counted=" + Money.fromMinor(counted) + ", variance=" + Money.fromMinor(counted - expected));
            return Db.queryOne(c, SELECT + "WHERE s.id = ?", ShiftService::map, id);
        });
    }

    /** Administrators/managers review every shift; cashiers see only their own. */
    public List<Shift> list(Session s, String from, String to) {
        s.require(Permission.SHIFT_OWN);
        boolean all = s.has(Permission.SHIFT_REVIEW);
        return db.read(c -> Db.query(c, SELECT + "WHERE date(s.opened_at) >= ? AND date(s.opened_at) <= ? AND (? = 1 OR s.user_id = ?) ORDER BY s.id DESC LIMIT 1000",
                ShiftService::map, from == null ? "0000-01-01" : from, to == null ? "9999-12-31" : to, all ? 1 : 0, s.userId()));
    }
}
