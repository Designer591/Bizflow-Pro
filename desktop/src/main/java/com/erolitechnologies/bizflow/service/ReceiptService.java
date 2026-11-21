package com.erolitechnologies.bizflow.service;

import com.erolitechnologies.bizflow.domain.Money;
import com.erolitechnologies.bizflow.infra.AppException;
import com.erolitechnologies.bizflow.infra.Db;
import com.erolitechnologies.bizflow.security.Permission;
import com.erolitechnologies.bizflow.security.Session;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Builds receipt text for 58mm (32 chars), 80mm (48 chars) or A4 (80 chars) paper. Reprints are marked as copies. */
public final class ReceiptService {
    private final Db db;

    public ReceiptService(Db db) { this.db = db; }

    public static int widthFor(String paper) {
        return switch (paper == null ? "80mm" : paper) { case "58mm" -> 32; case "A4" -> 80; default -> 48; };
    }

    public List<String> render(Session s, long saleId, boolean reprint) {
        s.require(Permission.SALES_HISTORY_VIEW);
        return db.tx(c -> {
            if (!s.has(Permission.SALES_HISTORY_ALL) && Db.scalarLong(c, "SELECT COUNT(*) FROM sales WHERE id = ? AND cashier_id = ?", saleId, s.userId()) == 0)
                throw new AppException("You can only print your own receipts.");
            int w = widthFor(SettingsService.get(c, "receipt_paper"));
            String sym = SettingsService.get(c, "currency_symbol");
            String[] biz = Db.queryOne(c, "SELECT name, COALESCE(address,''), COALESCE(phone,''), COALESCE(email,'') FROM businesses LIMIT 1",
                    rs -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)});
            record Head(String receipt, String when, String cashier, String customer, long sub, long disc, long tax, long total, String status) {}
            Head h = Db.queryOne(c, "SELECT s.receipt_no, s.created_at, u.full_name, COALESCE(cu.name,''), s.subtotal_minor, s.discount_minor, s.tax_minor, s.total_minor, s.status " +
                    "FROM sales s JOIN users u ON u.id = s.cashier_id LEFT JOIN customers cu ON cu.id = s.customer_id WHERE s.id = ?",
                    rs -> new Head(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getString(9)), saleId);
            if (h == null) throw new AppException("Sale not found.");

            List<String> out = new ArrayList<>();
            String header = SettingsService.get(c, "receipt_header");
            out.add(center(biz[0].toUpperCase(), w));
            for (String line : new String[]{biz[1], biz[2], biz[3], header}) if (!line.isBlank()) out.add(center(line, w));
            out.add(rule(w));
            if (reprint) out.add(center("*** COPY - REPRINT ***", w));
            if (h.status().equals("VOIDED")) out.add(center("*** VOIDED SALE ***", w));
            out.add(pair("Receipt:", h.receipt(), w));
            out.add(pair("Date:", h.when(), w));
            out.add(pair("Cashier:", h.cashier(), w));
            if (!h.customer().isBlank()) out.add(pair("Customer:", h.customer(), w));
            out.add(rule(w));
            for (Object[] it : Db.query(c, "SELECT description, qty_milli, unit_price_minor, line_total_minor, discount_minor FROM sale_items WHERE sale_id = ? ORDER BY id",
                    rs -> new Object[]{rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)}, saleId)) {
                String name = (String) it[0];
                out.add(name.length() > w ? name.substring(0, w) : name);
                String qty = Money.qtyFromMilli((Long) it[1]).stripTrailingZeros().toPlainString();
                out.add(pair("  " + qty + " x " + Money.fromMinor((Long) it[2]), sym + Money.fromMinor((Long) it[3]), w));
                if ((Long) it[4] > 0) out.add(pair("  discount", "-" + sym + Money.fromMinor((Long) it[4]), w));
            }
            out.add(rule(w));
            out.add(pair("Subtotal", sym + Money.fromMinor(h.sub()), w));
            if (h.disc() > 0) out.add(pair("Discount", "-" + sym + Money.fromMinor(h.disc()), w));
            if (h.tax() > 0) out.add(pair(SettingsService.get(c, "tax_name"), sym + Money.fromMinor(h.tax()), w));
            out.add(pair("TOTAL", sym + Money.fromMinor(h.total()), w));
            out.add(rule(w));
            long change = 0;
            for (Object[] p : Db.query(c, "SELECT method, amount_minor, tendered_minor, change_minor FROM payments WHERE sale_id = ? AND direction = 'IN' ORDER BY id",
                    rs -> new Object[]{rs.getString(1), rs.getLong(2), rs.getObject(3), rs.getLong(4)}, saleId)) {
                long shown = p[2] == null ? (Long) p[1] : ((Number) p[2]).longValue();
                out.add(pair((String) p[0], sym + Money.fromMinor(shown), w));
                change += (Long) p[3];
            }
            if (change > 0) out.add(pair("Change due", sym + Money.fromMinor(change), w));
            out.add(rule(w));
            String footer = SettingsService.get(c, "receipt_footer");
            for (String f : footer.split("\n")) if (!f.isBlank()) out.add(center(f, w));
            return out;
        });
    }

    static String center(String t, int w) {
        if (t.length() >= w) return t.substring(0, w);
        int pad = (w - t.length()) / 2;
        return " ".repeat(pad) + t;
    }

    static String rule(int w) { return "-".repeat(w); }

    static String pair(String left, String right, int w) {
        int space = w - left.length() - right.length();
        if (space < 1) {
            int keep = Math.max(1, w - right.length() - 1);
            left = left.substring(0, Math.min(left.length(), keep));
            space = Math.max(1, w - left.length() - right.length());
        }
        return left + " ".repeat(space) + right;
    }
}
