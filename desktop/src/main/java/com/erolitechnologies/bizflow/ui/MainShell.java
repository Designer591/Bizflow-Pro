package com.erolitechnologies.bizflow.ui;

import com.erolitechnologies.bizflow.App;
import com.erolitechnologies.bizflow.AppContext;
import com.erolitechnologies.bizflow.licensing.Edition;
import com.erolitechnologies.bizflow.security.Permission;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.InputEvent;
import javafx.scene.layout.*;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Sidebar + top bar + content. Navigation shows only what the user's role and the licence edition allow. */
public final class MainShell extends View {
    private record Nav(String label, String icon, Permission perm, Edition.Feature feature, Supplier<Node> factory) {}

    private final StackPane content = new StackPane();
    private final ToggleGroup group = new ToggleGroup();
    private final Label title = new Label();

    public MainShell(AppContext ctx, App app) { super(ctx, app); }

    @Override
    public Node build() {
        List<Nav> all = List.of(
                new Nav("Dashboard", "mdi2v-view-dashboard-outline", Permission.DASHBOARD_VIEW, null, () -> new DashboardView(ctx, app).build()),
                new Nav("Point of Sale", "mdi2c-cart-outline", Permission.POS_SELL, Edition.Feature.POS, () -> new PosView(ctx, app).build()),
                new Nav("Sales History", "mdi2r-receipt-text-outline", Permission.SALES_HISTORY_VIEW, Edition.Feature.POS, () -> new SalesHistoryView(ctx, app).build()),
                new Nav("Inventory", "mdi2p-package-variant-closed", Permission.PRODUCT_VIEW, Edition.Feature.INVENTORY, () -> new ProductsView(ctx, app).build()),
                new Nav("Customers", "mdi2a-account-group-outline", Permission.CUSTOMER_VIEW, Edition.Feature.CUSTOMERS, () -> new CustomersView(ctx, app).build()),
                new Nav("Suppliers & Purchasing", "mdi2t-truck-outline", Permission.SUPPLIER_MANAGE, Edition.Feature.SUPPLIERS_PURCHASING, () -> new SuppliersView(ctx, app).build()),
                new Nav("Finance", "mdi2c-cash-multiple", Permission.FINANCE_VIEW, Edition.Feature.FINANCE, () -> new FinanceView(ctx, app).build()),
                new Nav("Shifts", "mdi2c-cash-register", Permission.SHIFT_OWN, Edition.Feature.EMPLOYEES_SHIFTS, () -> new ShiftsView(ctx, app).build()),
                new Nav("People & Access", "mdi2a-account-cog-outline", Permission.USER_MANAGE, null, () -> new PeopleView(ctx, app).build()),
                new Nav("Reports", "mdi2c-chart-bar", Permission.REPORT_VIEW, Edition.Feature.BASIC_REPORTS, () -> new ReportsView(ctx, app).build()),
                new Nav("Backup & Restore", "mdi2d-database-export-outline", Permission.BACKUP_MANAGE, Edition.Feature.BACKUP, () -> new BackupView(ctx, app).build()),
                new Nav("Audit Log", "mdi2h-history", Permission.AUDIT_VIEW, null, () -> new AuditView(ctx, app).build()),
                new Nav("Settings", "mdi2c-cog-outline", Permission.SETTINGS_MANAGE, null, () -> new SettingsView(ctx, app).build()));

        List<Nav> allowed = new ArrayList<>();
        for (Nav n : all) if (s().has(n.perm()) && (n.feature() == null || ctx.licence.hasFeature(n.feature()))) allowed.add(n);

        Label logo = new Label("BizFlow Pro");
        logo.getStyleClass().add("sidebar-brand");
        Label by = new Label("EROLITECHNOLOGIES");
        by.getStyleClass().add("sidebar-sub");
        VBox sidebar = new VBox(4, logo, by);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPadding(new Insets(18, 12, 12, 12));
        sidebar.setPrefWidth(230);
        sidebar.getChildren().add(new Region() {{ setMinHeight(14); }});
        ToggleButton first = null;
        for (Nav n : allowed) {
            ToggleButton b = new ToggleButton(n.label());
            b.setToggleGroup(group);
            b.setMaxWidth(Double.MAX_VALUE);
            b.setAlignment(Pos.CENTER_LEFT);
            b.getStyleClass().add("nav-button");
            Node ic = Ui.icon(n.icon());
            if (ic != null) b.setGraphic(ic);
            b.setOnAction(e -> open(n));
            if (!b.isSelected() && group.getSelectedToggle() == null && first == null) first = b;
            sidebar.getChildren().add(b);
            b.setUserData(n);
        }
        Label lic = new Label(licenceLine());
        lic.getStyleClass().add("sidebar-sub");
        lic.setWrapText(true);
        sidebar.getChildren().addAll(Ui.spacer(), lic);

        // keep a selected button even if the user clicks the already selected one
        group.selectedToggleProperty().addListener((o, old, now) -> { if (now == null && old != null) group.selectToggle(old); });

        HBox top = topBar();
        BorderPane root = new BorderPane();
        root.setLeft(sidebar);
        root.setTop(top);
        root.setCenter(content);
        content.getStyleClass().add("content");

        if (first != null) { first.setSelected(true); open((Nav) first.getUserData()); }
        else content.getChildren().setAll(Ui.page("Welcome", Ui.muted("Your account has no screens enabled. Ask an administrator to review your role.")));

        // session handling: any input counts as activity; idle sessions sign out
        root.addEventFilter(InputEvent.ANY, e -> { if (ctx.session != null) ctx.session.touch(); });
        Timeline idle = new Timeline(new KeyFrame(Duration.seconds(30), e -> {
            if (ctx.session != null && ctx.session.isExpired()) {
                ctx.session = null;
                app.showEntry();
                Ui.info("You were signed out after a period of inactivity.");
            }
        }));
        idle.setCycleCount(Timeline.INDEFINITE);
        idle.play();
        return root;
    }

    private void open(Nav n) {
        title.setText(n.label());
        try {
            content.getChildren().setAll(n.factory().get());
        } catch (RuntimeException e) {
            Ui.error(e);
            content.getChildren().setAll(Ui.page(n.label(), Ui.muted("This screen could not be opened.")));
        }
    }

    private String licenceLine() {
        var st = ctx.licence.status();
        return (st.edition() == null ? "Evaluation" : st.edition().name().charAt(0) + st.edition().name().substring(1).toLowerCase() + " edition") + "\n" + st.message();
    }

    private HBox topBar() {
        title.getStyleClass().add("topbar-title");
        TextField search = Ui.field("Search products, customers...");
        search.setPrefWidth(320);
        search.setOnAction(e -> Ui.guard(() -> globalSearch(search.getText())));

        MenuButton bell = new MenuButton();
        Node bi = Ui.icon("mdi2b-bell-outline");
        if (bi != null) bell.setGraphic(bi); else bell.setText("Alerts");
        bell.getStyleClass().add("icon-menu");
        bell.setOnShowing(e -> {
            bell.getItems().clear();
            for (String n : notifications()) bell.getItems().add(new MenuItem(n));
            if (bell.getItems().isEmpty()) bell.getItems().add(new MenuItem("Nothing needs your attention."));
        });

        MenuButton me = new MenuButton(s().fullName());
        me.getStyleClass().add("profile-menu");
        MenuItem pw = new MenuItem("Change password");
        pw.setOnAction(e -> Ui.guard(this::changePassword));
        MenuItem theme = new MenuItem("Switch light / dark theme");
        theme.setOnAction(e -> Ui.guard(() -> {
            ctx.settings.putInternal("theme", "dark".equalsIgnoreCase(ctx.settings.get("theme")) ? "light" : "dark");
            app.applyTheme(null);
        }));
        MenuItem out = new MenuItem("Sign out");
        out.setOnAction(e -> Ui.guard(() -> { ctx.auth.logout(ctx.session); ctx.session = null; app.showEntry(); }));
        me.getItems().addAll(pw, theme, new SeparatorMenuItem(), out);

        HBox bar = new HBox(14, title, Ui.spacer(), search, bell, me);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("topbar");
        bar.setPadding(new Insets(10, 20, 10, 20));
        return bar;
    }

    private List<String> notifications() {
        List<String> out = new ArrayList<>();
        try {
            if (s().has(Permission.PRODUCT_VIEW)) {
                int low = ctx.products.search(s(), null, null, false, true).size();
                if (low > 0) out.add(low + " product(s) are at or below minimum stock");
            }
            if (s().has(Permission.BACKUP_MANAGE)) {
                var b = ctx.backup.status();
                if (b.lastError() != null && !b.lastError().isBlank()) out.add("Last backup failed: " + b.lastError());
                else if (b.overdue()) out.add("No recent backup. Open Backup & Restore.");
            }
            var l = ctx.licence.status();
            if (l.state() == com.erolitechnologies.bizflow.licensing.LicenceVerifier.State.EXPIRING_SOON || l.state() == com.erolitechnologies.bizflow.licensing.LicenceVerifier.State.IN_GRACE
                    || l.evaluation()) out.add(l.message());
        } catch (RuntimeException e) {
            com.erolitechnologies.bizflow.infra.Log.warn("Notifications failed: " + e.getMessage());
        }
        return out;
    }

    private void globalSearch(String text) {
        if (text == null || text.isBlank()) return;
        TableView<String[]> t = Ui.table("No matches");
        Ui.col(t, "Type", r -> r[0], 110);
        Ui.col(t, "Name", r -> r[1], 260);
        Ui.col(t, "Details", r -> r[2], 300);
        if (s().has(Permission.PRODUCT_VIEW))
            for (var p : ctx.products.search(s(), text, null, false, false))
                t.getItems().add(new String[]{"Product", p.name(), p.sku() + "  |  " + Ui.money(p.price()) + "  |  stock " + Ui.qty(p.stock())});
        if (s().has(Permission.CUSTOMER_VIEW))
            for (var c : ctx.customers.search(s(), text, false))
                t.getItems().add(new String[]{"Customer", c.name(), (c.phone() == null ? "" : c.phone())});
        t.setPrefSize(720, 360);
        Ui.dialog("Search results for \"" + text + "\"", t, "Close", () -> { });
    }

    private void changePassword() {
        PasswordField old = new PasswordField(), n1 = new PasswordField(), n2 = new PasswordField();
        GridPane g = Ui.form();
        Ui.addRow(g, 0, "Current password", old);
        Ui.addRow(g, 1, "New password", n1);
        Ui.addRow(g, 2, "Repeat new password", n2);
        Ui.dialog("Change password", g, "Change", () -> {
            if (!n1.getText().equals(n2.getText())) throw new com.erolitechnologies.bizflow.infra.AppException("The two new passwords do not match.");
            ctx.auth.changePassword(s(), old.getText(), n1.getText());
            Ui.info("Password changed.");
        });
    }
}
