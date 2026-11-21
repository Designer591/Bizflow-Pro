package com.erolitechnologies.bizflow.ui;

import com.erolitechnologies.bizflow.App;
import com.erolitechnologies.bizflow.AppContext;
import com.erolitechnologies.bizflow.infra.AppException;
import com.erolitechnologies.bizflow.service.SetupService;
import com.erolitechnologies.bizflow.service.Validate;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** First-launch wizard: business, currency and tax, receipts, administrator. Everything is saved in one transaction at the end. */
public final class SetupWizardView extends View {
    private record Cur(String code, String symbol, String label) {}

    private static final List<Cur> CURRENCIES = List.of(
            new Cur("USD", "$", "US Dollar (USD)"), new Cur("ZiG", "ZiG", "Zimbabwe Gold (ZiG)"), new Cur("ZAR", "R", "South African Rand (ZAR)"),
            new Cur("BWP", "P", "Botswana Pula (BWP)"), new Cur("GBP", "£", "British Pound (GBP)"), new Cur("EUR", "€", "Euro (EUR)"), new Cur("", "", "Other..."));

    private static final List<String> TYPES = List.of("Supermarket", "Grocery store", "Clothing boutique", "Hardware shop", "Electronics shop", "Wholesaler", "General retail", "Other");

    private final StackPane stepHost = new StackPane();
    private final Label stepTitle = Ui.h2("");
    private final Label progress = Ui.muted("");
    private int step = 0;
    private Button backBtn;
    private Button nextBtn;

    private final TextField name = Ui.field("e.g. Moyo Family Supermarket");
    private final ComboBox<String> type = new ComboBox<>();
    private final TextField logo = Ui.field("Optional - choose an image");
    private final TextField address = Ui.field("Street, town");
    private final TextField phone = Ui.field("+263 ...");
    private final TextField email = Ui.field("shop@example.com");
    private final TextField country = Ui.field("Country");
    private final ComboBox<Cur> currency = Ui.combo(CURRENCIES, Cur::label);
    private final TextField otherCode = Ui.field("Currency code, e.g. MWK");
    private final TextField otherSymbol = Ui.field("Symbol, e.g. MK");
    private final CheckBox taxOn = new CheckBox("Charge tax on sales");
    private final TextField taxName = Ui.field("VAT");
    private final TextField taxRate = Ui.field("15");
    private final CheckBox taxIncl = new CheckBox("Prices already include tax");
    private final TextField header = Ui.field("Extra line at the top of receipts (optional)");
    private final TextField footer = Ui.field("Thank you for shopping with us!");
    private final ComboBox<String> paper = new ComboBox<>();
    private final TextField adminName = Ui.field("Your full name");
    private final TextField adminUser = Ui.field("Username, e.g. owner");
    private final PasswordField pw1 = new PasswordField();
    private final PasswordField pw2 = new PasswordField();
    private final CheckBox demo = new CheckBox("Load sample products and customers so I can try the system");

    public SetupWizardView(AppContext ctx, App app) {
        super(ctx, app);
        type.getItems().addAll(TYPES);
        type.setValue(TYPES.get(0));
        type.setMaxWidth(Double.MAX_VALUE);
        paper.getItems().addAll("80mm", "58mm", "A4");
        paper.setValue("80mm");
        paper.setMaxWidth(Double.MAX_VALUE);
        country.setText("Zimbabwe");
        currency.getSelectionModel().select(0);
        taxName.setText("VAT");
        taxRate.setText("15");
    }

    @Override
    public Node build() {
        Label brand = new Label("BizFlow Pro");
        brand.getStyleClass().add("login-title");
        Label welcome = new Label("Let's set up your business");
        welcome.getStyleClass().add("login-tag");
        Label by = new Label("Powered by EROLITECHNOLOGIES");
        by.getStyleClass().add("login-by");
        VBox left = new VBox(10, brand, welcome, Ui.spacer(), by);
        left.getStyleClass().add("login-left");
        left.setPadding(new Insets(48));
        left.setPrefWidth(380);

        backBtn = Ui.button("Back", () -> { if (step > 0) { step--; show(); } });
        nextBtn = Ui.primary("Next", () -> {
            validateStep();
            if (step == 3) finish(); else { step++; show(); }
        });
        HBox nav = Ui.row(10, backBtn, Ui.spacer(), nextBtn);
        VBox card = new VBox(14, progress, stepTitle, stepHost, nav);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(28));
        card.setMaxWidth(620);
        StackPane right = new StackPane(card);
        right.setAlignment(Pos.CENTER);
        right.getStyleClass().add("login-right");
        HBox.setHgrow(right, Priority.ALWAYS);
        show();
        return new HBox(left, right);
    }

    private void show() {
        progress.setText("Step " + (step + 1) + " of 4");
        stepHost.getChildren().setAll(switch (step) {
            case 0 -> stepBusiness();
            case 1 -> stepMoney();
            case 2 -> stepReceipt();
            default -> stepAdmin();
        });
        stepTitle.setText(new String[]{"Your business", "Currency and tax", "Receipts", "Administrator account"}[step]);
        if (backBtn != null) backBtn.setDisable(step == 0);
        if (nextBtn != null) nextBtn.setText(step == 3 ? "Finish setup" : "Next");
    }

    private Node stepBusiness() {
        GridPane g = Ui.form();
        Button browse = Ui.button("Browse", () -> {
            File f = Ui.chooseOpen(app.stage(), "Choose your logo", "Images", "*.png", "*.jpg", "*.jpeg");
            if (f != null) logo.setText(f.getAbsolutePath());
        });
        Ui.addRow(g, 0, "Business name *", name);
        Ui.addRow(g, 1, "Business type *", type);
        Ui.addRow(g, 2, "Logo", Ui.row(8, logo, browse));
        HBox.setHgrow(logo, Priority.ALWAYS);
        Ui.addRow(g, 3, "Address", address);
        Ui.addRow(g, 4, "Phone", phone);
        Ui.addRow(g, 5, "Email", email);
        Ui.addRow(g, 6, "Country *", country);
        return g;
    }

    private Node stepMoney() {
        GridPane g = Ui.form();
        Ui.addRow(g, 0, "Currency *", currency);
        Ui.addRow(g, 1, "Other code", otherCode);
        Ui.addRow(g, 2, "Other symbol", otherSymbol);
        Ui.addRow(g, 3, "", taxOn);
        Ui.addRow(g, 4, "Tax name", taxName);
        Ui.addRow(g, 5, "Tax rate (%)", taxRate);
        Ui.addRow(g, 6, "", taxIncl);
        Node note = Ui.muted("You can change all of this later in Settings. Zimbabwe businesses can use USD and ZiG; more currencies can be typed in under 'Other'.");
        g.add(note, 0, 7, 2, 1);
        return g;
    }

    private Node stepReceipt() {
        GridPane g = Ui.form();
        Ui.addRow(g, 0, "Header line", header);
        Ui.addRow(g, 1, "Thank-you message", footer);
        Ui.addRow(g, 2, "Paper width", paper);
        g.add(Ui.muted("80mm and 58mm are the common thermal printer widths. A4 prints on a normal printer."), 0, 3, 2, 1);
        return g;
    }

    private Node stepAdmin() {
        GridPane g = Ui.form();
        Ui.addRow(g, 0, "Full name *", adminName);
        Ui.addRow(g, 1, "Username *", adminUser);
        Ui.addRow(g, 2, "Password *", pw1);
        Ui.addRow(g, 3, "Repeat password *", pw2);
        Ui.addRow(g, 4, "", demo);
        g.add(Ui.muted("At least 8 characters with letters and numbers. Keep it safe: it controls all of your business data."), 0, 5, 2, 1);
        return g;
    }

    private void validateStep() {
        switch (step) {
            case 0 -> {
                Validate.required(name.getText(), "Business name");
                Validate.required(country.getText(), "Country");
                Validate.email(email.getText());
                Validate.phone(phone.getText());
            }
            case 1 -> {
                Cur c = currency.getValue();
                if (c == null) throw new AppException("Choose a currency.");
                if (c.code().isEmpty()) { Validate.required(otherCode.getText(), "Currency code"); Validate.required(otherSymbol.getText(), "Currency symbol"); }
                if (taxOn.isSelected()) {
                    BigDecimal r = Validate.decimal(taxRate.getText(), "Tax rate");
                    if (r.signum() < 0 || r.compareTo(new BigDecimal("100")) > 0) throw new AppException("Tax rate must be between 0 and 100.");
                }
            }
            case 3 -> {
                Validate.required(adminName.getText(), "Your name");
                Validate.required(adminUser.getText(), "Username");
                if (!pw1.getText().equals(pw2.getText())) throw new AppException("The two passwords do not match.");
                com.erolitechnologies.bizflow.security.PasswordPolicy.check(pw1.getText());
            }
            default -> { }
        }
    }

    private void finish() {
        Cur c = currency.getValue();
        String code = c.code().isEmpty() ? otherCode.getText().trim().toUpperCase() : c.code();
        String sym = c.code().isEmpty() ? otherSymbol.getText().trim() : c.symbol();
        String logoPath = copyLogo();
        BigDecimal rate = taxOn.isSelected() ? new BigDecimal(taxRate.getText().trim()) : BigDecimal.ZERO;
        ctx.setup.complete(new SetupService.SetupData(name.getText(), type.getValue(), logoPath, address.getText(), phone.getText(), email.getText(),
                country.getText(), code, sym, taxOn.isSelected(), taxName.getText(), rate, taxIncl.isSelected(),
                header.getText(), footer.getText(), paper.getValue(), adminUser.getText(), adminName.getText(), pw1.getText(), demo.isSelected()));
        Ui.info("Setup complete. Sign in with the administrator account you just created.");
        app.showEntry();
    }

    private String copyLogo() {
        String p = logo.getText();
        if (p == null || p.isBlank()) return null;
        try {
            String ext = p.contains(".") ? p.substring(p.lastIndexOf('.')).toLowerCase() : ".png";
            Path dest = ctx.paths.assetsDir().resolve("logo" + ext);
            Files.copy(Path.of(p), dest, StandardCopyOption.REPLACE_EXISTING);
            return dest.toString();
        } catch (java.io.IOException | RuntimeException e) {
            com.erolitechnologies.bizflow.infra.Log.warn("Logo could not be copied: " + e.getMessage());
            return null;
        }
    }
}
