package com.erolitechnologies.bizflow.service;

import com.erolitechnologies.bizflow.infra.Db;
import com.erolitechnologies.bizflow.security.PasswordHasher;
import com.erolitechnologies.bizflow.security.Role;

import java.sql.Connection;
import java.sql.SQLException;

/** Optional sample catalogue offered by the setup wizard so new owners can try the system. Never loaded silently. */
public final class DemoData {
    private DemoData() {}

    public static void load(Connection c, long branchId, long adminId, PasswordHasher hasher) throws SQLException {
        String[] cats = {"Groceries", "Beverages", "Household", "Electronics", "Clothing", "Hardware"};
        long[] catId = new long[cats.length];
        for (int i = 0; i < cats.length; i++) catId[i] = Db.insert(c, "INSERT INTO categories(name) VALUES (?)", cats[i]);

        long s1 = Db.insert(c, "INSERT INTO suppliers(name, contact_name, phone, email, address) VALUES (?,?,?,?,?)", "Harare Wholesale Distributors", "T. Moyo", "+263 242 700100", "orders@hwd.example", "12 Industrial Rd, Harare");
        long s2 = Db.insert(c, "INSERT INTO suppliers(name, contact_name, phone, email, address) VALUES (?,?,?,?,?)", "Bulawayo Trading Co", "S. Ndlovu", "+263 292 800200", "sales@btc.example", "5 Fife St, Bulawayo");

        Object[][] products = {
                {0, s1, "Maize Meal 10kg", "6001001000011", 7.50, 9.50, 40, 10},
                {0, s1, "Cooking Oil 2L", "6001001000028", 4.20, 5.60, 60, 12},
                {0, s1, "Sugar 2kg", "6001001000035", 2.40, 3.30, 80, 15},
                {0, s1, "Rice 2kg", "6001001000042", 2.80, 3.80, 50, 10},
                {1, s2, "Mineral Water 500ml", "6001001000059", 0.35, 0.60, 200, 48},
                {1, s2, "Soft Drink 2L", "6001001000066", 1.10, 1.70, 90, 24},
                {2, s1, "Laundry Soap Bar", "6001001000073", 0.40, 0.75, 150, 30},
                {2, s1, "Dishwashing Liquid 750ml", "6001001000080", 1.60, 2.40, 35, 8},
                {3, s2, "Phone Charger USB-C", "6001001000097", 2.50, 5.00, 25, 5},
                {3, s2, "LED Bulb 9W", "6001001000103", 0.90, 1.80, 70, 15},
                {4, s2, "Cotton T-Shirt", "6001001000110", 3.00, 7.00, 30, 6},
                {5, s1, "Claw Hammer", "6001001000127", 4.00, 8.50, 12, 3},
                {5, s1, "Cement 50kg", "6001001000134", 8.00, 11.00, 5, 10}  // deliberately low to demo low-stock alerts
        };
        int n = 1;
        for (Object[] p : products) {
            long id = Db.insert(c, "INSERT INTO products(category_id, supplier_id, sku, barcode, name, cost_minor, price_minor, min_stock_milli) VALUES (?,?,?,?,?,?,?,?)",
                    catId[(Integer) p[0]], p[1], String.format("PRD-%06d", n++), p[3], p[2], Math.round((Double) p[4] * 100), Math.round((Double) p[5] * 100), (Integer) p[7] * 1000L);
            StockService.apply(c, branchId, id, java.math.BigDecimal.valueOf((Integer) p[6]), "OPENING", "demo", null, "Demo opening stock", adminId, false);
        }
        Db.insert(c, "INSERT INTO customers(name, phone, email, address, credit_enabled, credit_limit_minor) VALUES (?,?,?,?,1,?)", "Tendai Chikwanha", "+263 77 123 4567", "tendai@example.com", "Mbare, Harare", 20000L);
        Db.insert(c, "INSERT INTO customers(name, phone) VALUES (?,?)", "Walk-in regular: Mrs Dube", "+263 71 765 4321");
        AuditService.log(c, null, "DEMO_DATA_LOADED", "setup", null, products.length + " sample products");
    }
}
