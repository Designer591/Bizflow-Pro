package com.erolitechnologies.bizflow.testsupport;

import com.erolitechnologies.bizflow.infra.Db;
import com.erolitechnologies.bizflow.infra.Migrator;
import com.erolitechnologies.bizflow.security.PasswordHasher;
import com.erolitechnologies.bizflow.security.Session;
import com.erolitechnologies.bizflow.service.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

/** A fresh, fully migrated, set-up database in a temp folder for each test. */
public final class TestEnv {
    public final Path dir;
    public final com.erolitechnologies.bizflow.infra.AppPaths paths;
    public final Db db;
    public final PasswordHasher hasher = new FastHasher();
    public final SettingsService settings;
    public final AuthService auth;
    public final UserService users;
    public final ProductService products;
    public final StockService stock;
    public final CheckoutService checkout;
    public final RefundService refunds;
    public final ShiftService shifts;
    public final SetupService setup;
    public Session admin;

    public TestEnv() { this(false); }

    public TestEnv(boolean demo) {
        try {
            dir = Files.createTempDirectory("bizflow-test");
        } catch (java.io.IOException e) { throw new RuntimeException(e); }
        paths = new com.erolitechnologies.bizflow.infra.AppPaths(dir);
        db = Db.sqlite(paths.dbFile());
        new Migrator(db).migrate();
        RbacSeeder.ensure(db);
        settings = new SettingsService(db);
        auth = new AuthService(db, hasher, settings);
        users = new UserService(db, hasher);
        products = new ProductService(db);
        stock = new StockService(db);
        checkout = new CheckoutService(db);
        refunds = new RefundService(db);
        shifts = new ShiftService(db);
        setup = new SetupService(db, hasher);
        setup.complete(new SetupService.SetupData("Test Shop", "Retail", null, "1 Main St", "+263 77 000 0000", "shop@example.com",
                "Zimbabwe", "USD", "$", false, "VAT", new BigDecimal("15"), false, "", "Thanks!", "80mm",
                "admin", "Admin User", "Passw0rd!", demo));
        admin = auth.login("admin", "Passw0rd!");
        shifts.open(admin, new BigDecimal("50.00"));
    }

    public long product(String name, String cost, String price, String stockQty) {
        return products.create(admin, new ProductService.ProductInput(null, null, null, null, name, null, null, "each",
                new BigDecimal(cost), new BigDecimal(price), false, BigDecimal.ZERO, new BigDecimal(stockQty)));
    }

    public Session userWithRole(String username, com.erolitechnologies.bizflow.security.Role role) {
        users.create(admin, username, username + " Name", null, null, "Passw0rd!", role, null);
        return auth.login(username, "Passw0rd!");
    }

    /** Deterministic stand-in so tests do not depend on a BCrypt library being available offline. Production uses BcryptPasswordHasher. */
    static final class FastHasher implements PasswordHasher {
        @Override public String hash(String p) { return "test$" + new StringBuilder(p).reverse() + "$" + p.hashCode(); }
        @Override public boolean verify(String p, String h) { return p != null && h != null && h.equals(hash(p)); }
    }
}
