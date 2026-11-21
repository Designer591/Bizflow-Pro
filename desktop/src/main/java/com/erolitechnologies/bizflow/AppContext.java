package com.erolitechnologies.bizflow;

import com.erolitechnologies.bizflow.infra.AppPaths;
import com.erolitechnologies.bizflow.infra.Db;
import com.erolitechnologies.bizflow.licensing.LicenceVerifier;
import com.erolitechnologies.bizflow.security.BcryptPasswordHasher;
import com.erolitechnologies.bizflow.security.PasswordHasher;
import com.erolitechnologies.bizflow.security.Session;
import com.erolitechnologies.bizflow.service.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;

/** Wires the layers together once at start-up. UI classes receive this and never touch SQL. */
public final class AppContext {
    public final AppPaths paths;
    public final Db db;
    public final PasswordHasher hasher = new BcryptPasswordHasher();
    public final SettingsService settings;
    public final AuditService audit;
    public final BusinessService business;
    public final BranchService branches;
    public final AuthService auth;
    public final UserService users;
    public final EmployeeService employees;
    public final ProductService products;
    public final StockService stock;
    public final SupplierService suppliers;
    public final PurchaseService purchases;
    public final CustomerService customers;
    public final CheckoutService checkout;
    public final RefundService refunds;
    public final ShiftService shifts;
    public final SalesHistoryService salesHistory;
    public final ReceiptService receipts;
    public final ExpenseService expenses;
    public final FinanceService finance;
    public final DashboardService dashboard;
    public final ReportService reports;
    public final ReportExportService exports;
    public final BackupService backup;
    public final LicenceService licence;
    public final SetupService setup;

    /** The signed-in user, or null on the login screen. */
    public Session session;

    public AppContext(AppPaths paths, Db db) {
        this.paths = paths;
        this.db = db;
        settings = new SettingsService(db);
        audit = new AuditService(db);
        business = new BusinessService(db);
        branches = new BranchService(db);
        auth = new AuthService(db, hasher, settings);
        users = new UserService(db, hasher);
        employees = new EmployeeService(db);
        products = new ProductService(db);
        stock = new StockService(db);
        suppliers = new SupplierService(db);
        purchases = new PurchaseService(db);
        customers = new CustomerService(db);
        checkout = new CheckoutService(db);
        refunds = new RefundService(db);
        shifts = new ShiftService(db);
        salesHistory = new SalesHistoryService(db);
        receipts = new ReceiptService(db);
        expenses = new ExpenseService(db);
        finance = new FinanceService(db);
        dashboard = new DashboardService(db);
        reports = new ReportService(db);
        exports = new ReportExportService(business, audit);
        backup = new BackupService(db, paths, settings, audit);
        licence = new LicenceService(db, settings, loadVerifier(), LicenceService.computeDeviceId(), Clock.systemUTC());
        setup = new SetupService(db, hasher);
    }

    /** The PUBLIC verification key is bundled as a resource. If the build has none, only evaluation mode is available. */
    private static LicenceVerifier loadVerifier() {
        try (InputStream in = AppContext.class.getResourceAsStream("/licence-public-key.txt")) {
            if (in == null) return null;
            return LicenceVerifier.fromBase64PublicKey(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            com.erolitechnologies.bizflow.infra.Log.warn("Licence public key could not be loaded: " + e.getMessage());
            return null;
        }
    }
}
