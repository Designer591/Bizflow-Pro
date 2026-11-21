package com.erolitechnologies.bizflow.ui;

import com.erolitechnologies.bizflow.App;
import com.erolitechnologies.bizflow.AppContext;
import com.erolitechnologies.bizflow.infra.AppException;
import com.erolitechnologies.bizflow.service.BusinessService;
import com.erolitechnologies.bizflow.service.Validate;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SettingsView extends View {
    public static final String VERSION = "1.0.0";

    public SettingsView(AppContext ctx, App app) { super(ctx, app); }

    @Override
    public Node build() {
        TabPane tabs = new TabPane(tab("Business", business()), tab("Currency & tax", moneyTab()), tab("Receipts", receipts()), tab("Sales & security", sales()),
                tab("Appearance", appearance()), tab("Database", database()), tab("Updates", updates()), tab("Licence", new LicencePanel(ctx, app, () -> app.showMain()).build()));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        return Ui.page("Settings", tabs);
    }

    private static Tab tab(String name, Node n) {
        VBox box = new VBox(n);
        box.setPadding(new Insets(14));
        ScrollPane sp = new ScrollPane(box);
        sp.setFitToWidth(true);
        return new Tab(name, sp);
    }

    private void save(Map<String, String> m) {
        ctx.settings.update(s(), m);
        Ui.setCurrency(ctx.settings.get("currency_symbol"));
        Ui.info("Settings saved.");
    }

    private Node business() {
        BusinessService.Profile p = ctx.business.get();
        TextField name = Ui.field("Business name"), type = Ui.field("Business type"), logo = Ui.field("Logo file"), addr = Ui.field("Address"), phone = Ui.field("Phone"), email = Ui.field("Email"), country = Ui.field("Country");
        name.setText(p.name()); type.setText(p.type()); logo.setText(p.logoPath()); addr.setText(p.address()); phone.setText(p.phone()); email.setText(p.email()); country.setText(p.country());
        Button browse = Ui.button("Browse", () -> { var f = Ui.chooseOpen(app.stage(), "Logo", "Images", "*.png", "*.jpg", "*.jpeg"); if (f != null) logo.setText(f.getAbsolutePath()); });
        GridPane g = Ui.form();
        Ui.addRow(g, 0, "Business name *", name); Ui.addRow(g, 1, "Business type *", type); Ui.addRow(g, 2, "Logo", Ui.row(8, logo, browse));
        HBox.setHgrow(logo, Priority.ALWAYS);
        Ui.addRow(g, 3, "Address", addr); Ui.addRow(g, 4, "Phone", phone); Ui.addRow(g, 5, "Email", email); Ui.addRow(g, 6, "Country *", country);
        Button save = Ui.primary("Save", () -> {
            ctx.business.update(s(), new BusinessService.Profile(p.id(), name.getText(), type.getText(), logo.getText(), addr.getText(), phone.getText(), email.getText(), country.getText(), p.currencyCode()));
            Ui.info("Business information saved.");
        });
        return new VBox(12, g, save);
    }

    private Node moneyTab() {
        TextField code = Ui.field("USD"), sym = Ui.field("$"), taxName = Ui.field("VAT"), rate = Ui.field("15");
        code.setText(ctx.settings.get("currency_code")); sym.setText(ctx.settings.get("currency_symbol"));
        taxName.setText(ctx.settings.get("tax_name")); rate.setText(ctx.settings.get("tax_rate"));
        CheckBox on = new CheckBox("Charge tax on sales"), incl = new CheckBox("Prices already include tax");
        on.setSelected(ctx.settings.getBool("tax_enabled")); incl.setSelected(ctx.settings.getBool("tax_inclusive"));
        GridPane g = Ui.form();
        Ui.addRow(g, 0, "Currency code", code); Ui.addRow(g, 1, "Currency symbol", sym); Ui.addRow(g, 2, "", on);
        Ui.addRow(g, 3, "Tax name", taxName); Ui.addRow(g, 4, "Tax rate (%)", rate); Ui.addRow(g, 5, "", incl);
        Button save = Ui.primary("Save", () -> {
            Validate.required(code.getText(), "Currency code");
            Validate.required(sym.getText(), "Currency symbol");
            Map<String, String> m = new LinkedHashMap<>();
            m.put("currency_code", code.getText().trim().toUpperCase()); m.put("currency_symbol", sym.getText().trim());
            m.put("tax_enabled", String.valueOf(on.isSelected())); m.put("tax_name", taxName.getText().trim()); m.put("tax_rate", rate.getText().trim()); m.put("tax_inclusive", String.valueOf(incl.isSelected()));
            save(m);
        });
        return new VBox(12, Ui.muted("Changing currency or tax affects future sales only. Past receipts keep what was charged."), g, save);
    }

    private Node receipts() {
        TextField header = Ui.field("Header"), footer = Ui.field("Footer"), prefix = Ui.field("RCP");
        header.setText(ctx.settings.get("receipt_header")); footer.setText(ctx.settings.get("receipt_footer")); prefix.setText(ctx.settings.get("receipt_prefix"));
        ComboBox<String> paper = new ComboBox<>();
        paper.getItems().addAll("80mm", "58mm", "A4");
        paper.setValue(ctx.settings.get("receipt_paper"));
        CheckBox logo = new CheckBox("Show business logo on receipts");
        logo.setSelected(ctx.settings.getBool("receipt_show_logo"));
        CheckBox escpos = new CheckBox("Print straight to a thermal receipt printer (ESC/POS) without the print dialog");
        escpos.setSelected(ctx.settings.getBool("receipt_escpos"));
        ComboBox<String> printer = new ComboBox<>();
        printer.getItems().addAll(com.erolitechnologies.bizflow.infra.EscPosPrinter.printerNames());
        printer.setValue(ctx.settings.get("receipt_printer").isBlank() ? null : ctx.settings.get("receipt_printer"));
        printer.setMaxWidth(Double.MAX_VALUE);
        CheckBox drawer = new CheckBox("Open the cash drawer after printing");
        drawer.setSelected(ctx.settings.getBool("receipt_open_drawer"));
        Button test = Ui.button("Print test receipt", () -> com.erolitechnologies.bizflow.infra.EscPosPrinter.send(printer.getValue(),
                com.erolitechnologies.bizflow.infra.EscPosPrinter.build(java.util.List.of("BizFlow Pro", "Printer test OK", "--------------------------------"), drawer.isSelected())));
        GridPane g = Ui.form();
        Ui.addRow(g, 0, "Header line", header); Ui.addRow(g, 1, "Thank-you message", footer); Ui.addRow(g, 2, "Paper width", paper);
        Ui.addRow(g, 3, "Receipt number prefix", prefix); Ui.addRow(g, 4, "", logo);
        Ui.addRow(g, 5, "", escpos); Ui.addRow(g, 6, "Receipt printer", printer); Ui.addRow(g, 7, "", drawer); Ui.addRow(g, 8, "", test);
        Button save = Ui.primary("Save", () -> {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("receipt_header", header.getText()); m.put("receipt_footer", footer.getText()); m.put("receipt_paper", paper.getValue());
            m.put("receipt_prefix", Validate.required(prefix.getText(), "Receipt prefix")); m.put("receipt_show_logo", String.valueOf(logo.isSelected()));
            if (escpos.isSelected() && printer.getValue() == null) throw new com.erolitechnologies.bizflow.infra.AppException("Choose the receipt printer first.");
            m.put("receipt_escpos", String.valueOf(escpos.isSelected())); m.put("receipt_printer", printer.getValue() == null ? "" : printer.getValue()); m.put("receipt_open_drawer", String.valueOf(drawer.isSelected()));
            save(m);
        });
        return new VBox(12, g, save);
    }

    private Node sales() {
        CheckBox neg = new CheckBox("Allow selling below zero stock (administrators only; not recommended)");
        CheckBox shift = new CheckBox("Require an open cash shift before selling");
        neg.setSelected(ctx.settings.getBool("allow_negative_stock")); shift.setSelected(ctx.settings.getBool("require_open_shift"));
        TextField disc = Ui.field("10"), idle = Ui.field("30"), methods = Ui.field("Cash,EcoCash,Bank Transfer,Card");
        disc.setText(ctx.settings.get("max_discount_percent_cashier")); idle.setText(ctx.settings.get("session_idle_minutes")); methods.setText(ctx.settings.get("payment_methods"));
        GridPane g = Ui.form();
        g.add(neg, 0, 0, 2, 1); g.add(shift, 0, 1, 2, 1);
        Ui.addRow(g, 2, "Largest discount a cashier may give (%)", disc);
        Ui.addRow(g, 3, "Sign out after idle minutes", idle);
        Ui.addRow(g, 4, "Payment methods (comma separated)", methods);
        Button save = Ui.primary("Save", () -> {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("allow_negative_stock", String.valueOf(neg.isSelected())); m.put("require_open_shift", String.valueOf(shift.isSelected()));
            m.put("max_discount_percent_cashier", disc.getText().trim()); m.put("session_idle_minutes", idle.getText().trim()); m.put("payment_methods", methods.getText().trim());
            save(m);
        });
        return new VBox(12, g, Ui.muted("Payment methods appear at the till. 'Cash' should stay in the list: it is the only method that can give change."), save);
    }

    private Node appearance() {
        ComboBox<String> theme = new ComboBox<>();
        theme.getItems().addAll("light", "dark");
        theme.setValue(ctx.settings.get("theme"));
        Button save = Ui.primary("Apply", () -> { ctx.settings.update(s(), Map.of("theme", theme.getValue())); app.applyTheme(null); });
        GridPane g = Ui.form();
        Ui.addRow(g, 0, "Theme", theme);
        return new VBox(12, g, save);
    }

    private Node database() {
        GridPane g = Ui.form();
        Ui.addRow(g, 0, "Database", new Label(ctx.db.description()));
        Ui.addRow(g, 1, "Location", new Label(ctx.paths.dbFile().toString()));
        Ui.addRow(g, 2, "Logs", new Label(ctx.paths.logDir().toString()));
        Ui.addRow(g, 3, "Backups", new Label(ctx.backup.configuredDir().toString()));
        return new VBox(12, g, Ui.muted("This installation stores its data in a local SQLite database. The central multi-branch edition uses a server; see the Multi-branch section of the documentation. "
                + "Use Backup & Restore to protect this file."));
    }

    private Node updates() {
        CheckBox on = new CheckBox("Check for updates when I ask");
        on.setSelected(ctx.settings.getBool("update_check_enabled"));
        TextField url = Ui.field("https://updates.example.com/bizflow");
        url.setText(ctx.settings.get("update_url"));
        ComboBox<String> channel = new ComboBox<>();
        channel.getItems().addAll("stable", "beta");
        channel.setValue(ctx.settings.get("update_channel"));
        GridPane g = Ui.form();
        Ui.addRow(g, 0, "Installed version", new Label(VERSION));
        Ui.addRow(g, 1, "", on);
        Ui.addRow(g, 2, "Update address", url);
        Ui.addRow(g, 3, "Channel", channel);
        Button save = Ui.button("Save", () -> save(Map.of("update_check_enabled", String.valueOf(on.isSelected()), "update_url", url.getText().trim(), "update_channel", channel.getValue())));
        Button check = Ui.primary("Check now", () -> Ui.background(() -> checkUpdate(url.getText().trim(), channel.getValue()), Ui::info));
        return new VBox(12, g, Ui.row(8, save, check), Ui.muted("The update address must serve a text file <address>/<channel>.txt whose first line is the newest version and second line is the download page. "
                + "Updates are installed by running the new installer; your data is kept."));
    }

    private static String checkUpdate(String base, String channel) {
        if (base.isBlank()) throw new AppException("Enter the update address first.");
        URI u;
        try { u = URI.create(base.replaceAll("/+$", "") + "/" + channel + ".txt"); } catch (RuntimeException e) { throw new AppException("The update address is not valid."); }
        if (!"https".equalsIgnoreCase(u.getScheme())) throw new AppException("The update address must start with https://");
        try {
            HttpResponse<String> r = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()
                    .send(HttpRequest.newBuilder(u).timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) throw new AppException("The update server answered with an error (" + r.statusCode() + ").");
            String[] lines = r.body().trim().split("\\R");
            String latest = lines[0].trim();
            return latest.equals(VERSION) ? "You have the latest version (" + VERSION + ")." : "Version " + latest + " is available (you have " + VERSION + ")." + (lines.length > 1 ? "\nDownload: " + lines[1].trim() : "");
        } catch (java.io.IOException e) {
            throw new AppException("Could not reach the update server. Check your internet connection.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException("The update check was interrupted.");
        }
    }
}
