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

/**
 * The only code that changes stock levels. Every change is one atomic conditional UPDATE (so two cashiers
 * can never both sell the last unit) plus a stock_movements row recording the new balance.
 */
public final class StockService {
    public record Movement(long id, String when, String type, BigDecimal qty, BigDecimal balance, String note, String user) {}

    private final Db db;

    public StockService(Db db) { this.db = db; }

    /** @return the new balance */
    public static BigDecimal apply(Connection c, long branchId, long productId, BigDecimal deltaQty, String type,
                                   String refType, Long refId, String note, Long userId, boolean allowNegative) throws SQLException {
        long delta = Money.qtyToMilli(deltaQty);
        int updated = Db.update(c,
                "UPDATE branch_stock SET qty_milli = qty_milli + ? WHERE branch_id = ? AND product_id = ? AND (? = 1 OR qty_milli + ? >= 0)",
                delta, branchId, productId, allowNegative ? 1 : 0, delta);
        if (updated == 0) {
            boolean exists = Db.scalarLong(c, "SELECT COUNT(*) FROM branch_stock WHERE branch_id = ? AND product_id = ?", branchId, productId) > 0;
            if (!exists && (delta >= 0 || allowNegative)) {
                Db.update(c, "INSERT INTO branch_stock(branch_id, product_id, qty_milli) VALUES (?,?,?)", branchId, productId, delta);
            } else {
                String name = Db.queryOne(c, "SELECT name FROM products WHERE id = ?", rs -> rs.getString(1), productId);
                throw new InsufficientStockException(name == null ? "product #" + productId : name);
            }
        }
        long balance = Db.scalarLong(c, "SELECT qty_milli FROM branch_stock WHERE branch_id = ? AND product_id = ?", branchId, productId);
        Db.update(c, "INSERT INTO stock_movements(branch_id, product_id, type, qty_milli, balance_milli, ref_type, ref_id, note, user_id) VALUES (?,?,?,?,?,?,?,?,?)",
                branchId, productId, type, delta, balance, refType, refId, note, userId);
        return Money.qtyFromMilli(balance);
    }

    public static BigDecimal onHand(Connection c, long branchId, long productId) throws SQLException {
        return Money.qtyFromMilli(Db.scalarLong(c, "SELECT COALESCE((SELECT qty_milli FROM branch_stock WHERE branch_id = ? AND product_id = ?),0)", branchId, productId));
    }

    /** Manual stock adjustment (count correction, damage, etc.). A reason is mandatory. */
    public BigDecimal adjust(Session s, long productId, BigDecimal deltaQty, String reason) {
        s.require(Permission.STOCK_ADJUST);
        Validate.required(reason, "Reason");
        if (deltaQty == null || deltaQty.signum() == 0) throw new AppException("Enter a quantity to add or remove.");
        return db.tx(c -> {
            boolean allowNeg = SettingsService.getBool(c, "allow_negative_stock") && s.has(Permission.SETTINGS_MANAGE);
            BigDecimal bal = apply(c, s.branchId(), productId, deltaQty, "ADJUSTMENT", "adjustment", null, reason.trim(), s.userId(), allowNeg);
            AuditService.log(c, s, "STOCK_ADJUSTED", "product", productId, "change=" + deltaQty.toPlainString() + ", reason=" + reason.trim());
            return bal;
        });
    }

    /** Moves stock between branches in one transaction (Enterprise multi-branch). */
    public void transfer(Session s, long fromBranch, long toBranch, long productId, BigDecimal qty) {
        s.require(Permission.STOCK_ADJUST);
        if (fromBranch == toBranch) throw new AppException("Choose two different branches.");
        Validate.positive(qty, "Quantity");
        db.txVoid(c -> {
            long id = Db.insert(c, "INSERT INTO stock_transfers(from_branch_id, to_branch_id, product_id, qty_milli, created_by) VALUES (?,?,?,?,?)",
                    fromBranch, toBranch, productId, Money.qtyToMilli(qty), s.userId());
            apply(c, fromBranch, productId, qty.negate(), "TRANSFER_OUT", "transfer", id, "To branch " + toBranch, s.userId(), false);
            apply(c, toBranch, productId, qty, "TRANSFER_IN", "transfer", id, "From branch " + fromBranch, s.userId(), true);
            AuditService.log(c, s, "STOCK_TRANSFERRED", "product", productId, "qty=" + qty.toPlainString() + " from=" + fromBranch + " to=" + toBranch);
            return null;
        });
    }

    public List<Movement> movements(Session s, long productId, int limit) {
        s.require(Permission.PRODUCT_VIEW);
        return db.read(c -> Db.query(c,
                "SELECT m.id, m.created_at, m.type, m.qty_milli, m.balance_milli, COALESCE(m.note,''), COALESCE(u.username,'') " +
                "FROM stock_movements m LEFT JOIN users u ON u.id = m.user_id WHERE m.product_id = ? AND m.branch_id = ? ORDER BY m.id DESC LIMIT " + Math.max(1, Math.min(limit, 2000)),
                rs -> new Movement(rs.getLong(1), rs.getString(2), rs.getString(3), Money.qtyFromMilli(rs.getLong(4)), Money.qtyFromMilli(rs.getLong(5)), rs.getString(6), rs.getString(7)),
                productId, s.branchId()));
    }
}
