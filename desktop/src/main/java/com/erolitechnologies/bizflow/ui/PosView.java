package com.erolitechnologies.bizflow.ui;

import com.erolitechnologies.bizflow.App;
import com.erolitechnologies.bizflow.AppContext;
import com.erolitechnologies.bizflow.domain.sale.PaymentAllocator;
import com.erolitechnologies.bizflow.domain.sale.PaymentLine;
import com.erolitechnologies.bizflow.domain.sale.SaleCalculator;
import com.erolitechnologies.bizflow.domain.sale.SaleTotals;
import com.erolitechnologies.bizflow.infra.AppException;
import com.erolitechnologies.bizflow.security.Permission;
import com.erolitechnologies.bizflow.service.CheckoutService;
import com.erolitechnologies.bizflow.service.CustomerService;
import com.erolitechnologies.bizflow.service.ProductService;
import com.erolitechnologies.bizflow.service.Validate;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Checkout screen. The screen only collects intent (which products, how many, which discount, how paid);
 * CheckoutService re-reads prices from the database and does all validation inside one transaction.
 */
public final class PosView extends View {
    private static final class CartItem {
        final ProductService.Product product;
        BigDecimal qty = BigDecimal.ONE;
        BigDecimal discount = BigDecimal.ZERO;
        CartItem(ProductService.Product p) { this.product = p; }
    }

    private final ObservableList<CartItem> cart = FXCollections.observableArrayList();
    private final TableView<CartItem> cartTable = Ui.table("Scan or search a product to start a sale");
    private final TableView<ProductService.Product> results = Ui.table("No products found");
    private final TextField search = Ui.field("Scan barcode or type name / SKU, then press Enter");
    private final TextField cartDiscount = Ui.field("0.00");
    private final CheckBox discountIsPercent = new CheckBox("%");
    private final ComboBox<CustomerService.Customer> customer = new ComboBox<>();
    private final Label subtotal = new Label(), discountLbl = new Label(), tax = new Label(), total = new Label();
    private final VBox shiftBanner = new VBox();

    public PosView(AppContext ctx, App app) { super(ctx, app); }

    @Override
    public Node build() {
        // ---- left: product finder ----
        Ui.col(results, "Product", ProductService.Product::name, 220);
        Ui.col(results, "SKU", ProductService.Product::sku, 90);
        Ui.colRight(results, "Price", p -> Ui.money(p.price()), 80);
        Ui.colRight(results, "Stock", p -> Ui.qty(p.stock()), 60);
        results.setRowFactory(tv -> {
            TableRow<ProductService.Product> row = new TableRow<>();
            row.setOnMouseClicked(e -> { if (e.getClickCount() == 2 && !row.isEmpty()) addToCart(row.getItem()); });
            return row;
        });
        search.setOnAction(e -> Ui.guard(this::onSearchEnter));
        search.textProperty().addListener((o, a, b) -> { if (b != null && b.length() >= 2) Ui.guard(() -> results.getItems().setAll(ctx.products.search(s(), b, null, false, false))); });
        Button addSel = Ui.button("Add selected", () -> { var p = results.getSelectionModel().getSelectedItem(); if (p != null) addToCart(p); });
        VBox left = new VBox(10, Ui.h2("Products"), search, results, addSel);
        left.getStyleClass().add("card");
        left.setPrefWidth(520);
        HBox.setHgrow(left, Priority.SOMETIMES);

        // ---- right: cart ----
        Ui.col(cartTable, "Item", c -> c.product.name(), 190);
        Ui.colRight(cartTable, "Price", c -> Ui.money(c.product.price()), 70);
        Ui.colRight(cartTable, "Qty", c -> Ui.qty(c.qty), 60);
        Ui.colRight(cartTable, "Line disc.", c -> c.discount.signum() == 0 ? "" : Ui.money(c.discount), 70);
        Ui.colRight(cartTable, "Amount", c -> Ui.money(c.product.price().multiply(c.qty).subtract(c.discount)), 80);
        cartTable.setItems(cart);
        Button plus = Ui.button("+", () -> change(1));
        Button minus = Ui.button("-", () -> change(-1));
        Button qtyBtn = Ui.button("Set quantity", this::setQty);
        Button lineDisc = Ui.button("Line discount", this::setLineDiscount);
        Button remove = Ui.danger("Remove", () -> { var c = cartTable.getSelectionModel().getSelectedItem(); if (c != null) { cart.remove(c); recalc(); } });
        Button clear = Ui.button("Clear sale", () -> { if (cart.isEmpty() || Ui.confirm("Remove all items from this sale?")) { cart.clear(); recalc(); } });
        HBox cartTools = Ui.row(6, plus, minus, qtyBtn, lineDisc, remove, Ui.spacer(), clear);

        discountIsPercent.setOnAction(e -> recalc());
        cartDiscount.textProperty().addListener((o, a, b) -> recalc());
        cartDiscount.setPrefWidth(90);
        discountIsPercent.setSelected(false);
        HBox discRow = Ui.row(8, new Label("Discount on whole sale:"), cartDiscount, discountIsPercent);
        discRow.setDisable(!can(Permission.POS_DISCOUNT));
        customer.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(CustomerService.Customer c) { return c == null ? "Walk-in customer" : c.name(); }
            @Override public CustomerService.Customer fromString(String s) { return null; }
        });
        customer.setPromptText("Walk-in customer");
        customer.setMaxWidth(Double.MAX_VALUE);
        if (can(Permission.CUSTOMER_VIEW)) customer.getItems().addAll(ctx.customers.search(s(), null, false));
        Button noCust = Ui.button("Clear", () -> customer.setValue(null));
        HBox custRow = Ui.row(8, new Label("Customer:"), customer, noCust);
        HBox.setHgrow(customer, Priority.ALWAYS);

        for (Label l : List.of(subtotal, discountLbl, tax)) l.getStyleClass().add("totals-line");
        total.getStyleClass().add("totals-big");
        GridPane totals = new GridPane();
        totals.setHgap(20);
        totals.setVgap(4);
        totals.add(new Label("Subtotal"), 0, 0); totals.add(subtotal, 1, 0);
        totals.add(new Label("Discount"), 0, 1); totals.add(discountLbl, 1, 1);
        totals.add(new Label(ctx.settings.get("tax_name")), 0, 2); totals.add(tax, 1, 2);
        Label totalLabel = new Label("TOTAL");
        totalLabel.getStyleClass().add("totals-big");
        totals.add(totalLabel, 0, 3); totals.add(total, 1, 3);
        Button pay = Ui.primary("Charge / Pay", this::pay);
        pay.getStyleClass().add("pay-button");
        pay.setMaxWidth(Double.MAX_VALUE);

        VBox right = new VBox(10, shiftBanner, Ui.h2("Current sale"), cartTable, cartTools, discRow, custRow, new Separator(), totals, pay);
        right.getStyleClass().add("card");
        HBox.setHgrow(right, Priority.ALWAYS);
        refreshShiftBanner();
        recalc();
        HBox root = new HBox(14, left, right);
        root.setPadding(new Insets(16));
        Platform_focus();
        return root;
    }

    private void Platform_focus() { javafx.application.Platform.runLater(search::requestFocus); }

    private void refreshShiftBanner() {
        shiftBanner.getChildren().clear();
        if (!can(Permission.SHIFT_OWN)) return;
        var shift = ctx.shifts.currentShift(s());
        if (shift == null) {
            Label l = new Label("No open cash shift. Open one before selling.");
            l.getStyleClass().add("error-text");
            Button open = Ui.primary("Open shift", () -> {
                TextField floatAmt = Ui.field("0.00");
                GridPane g = Ui.form();
                Ui.addRow(g, 0, "Cash in drawer (opening float)", floatAmt);
                Ui.dialog("Open shift", g, "Open shift", () -> {
                    ctx.shifts.open(s(), floatAmt.getText().isBlank() ? BigDecimal.ZERO : Validate.decimal(floatAmt.getText(), "Opening cash"));
                    refreshShiftBanner();
                });
            });
            shiftBanner.getChildren().add(Ui.row(10, l, open));
        } else {
            shiftBanner.getChildren().add(Ui.muted("Shift open since " + Ui.date(shift.openedAt()) + "  |  float " + Ui.money(shift.opening())));
        }
    }

    private void onSearchEnter() {
        String text = search.getText();
        if (text == null || text.isBlank()) return;
        var exact = ctx.products.findByCode(s(), text);
        if (exact != null) { addToCart(exact); search.clear(); return; }
        var found = ctx.products.search(s(), text, null, false, false);
        results.getItems().setAll(found);
        if (found.size() == 1) { addToCart(found.get(0)); search.clear(); }
        else if (found.isEmpty()) Ui.toast("Not found", "No product matches \"" + text + "\"");
    }

    private void addToCart(ProductService.Product p) {
        for (CartItem c : cart) if (c.product.id() == p.id()) { c.qty = c.qty.add(BigDecimal.ONE); cartTable.refresh(); recalc(); return; }
        cart.add(new CartItem(p));
        recalc();
    }

    private void change(int delta) {
        CartItem c = cartTable.getSelectionModel().getSelectedItem();
        if (c == null) return;
        BigDecimal q = c.qty.add(BigDecimal.valueOf(delta));
        if (q.signum() <= 0) cart.remove(c); else c.qty = q;
        cartTable.refresh();
        recalc();
    }

    private void setQty() {
        CartItem c = cartTable.getSelectionModel().getSelectedItem();
        if (c == null) return;
        Ui.prompt("Quantity", "Quantity for " + c.product.name() + ":", Ui.qty(c.qty)).ifPresent(t -> Ui.guard(() -> {
            c.qty = Validate.positive(Validate.decimal(t, "Quantity"), "Quantity");
            cartTable.refresh();
            recalc();
        }));
    }

    private void setLineDiscount() {
        CartItem c = cartTable.getSelectionModel().getSelectedItem();
        if (c == null) return;
        if (!can(Permission.POS_DISCOUNT)) throw new com.erolitechnologies.bizflow.security.PermissionDeniedException(Permission.POS_DISCOUNT);
        Ui.prompt("Line discount", "Discount amount for this line:", Ui.plain(c.discount)).ifPresent(t -> Ui.guard(() -> {
            c.discount = Validate.nonNegative(Validate.decimal(t, "Discount"), "Discount");
            cartTable.refresh();
            recalc();
        }));
    }

    private List<CheckoutService.Line> lines() {
        List<CheckoutService.Line> l = new ArrayList<>();
        for (CartItem c : cart) l.add(new CheckoutService.Line(c.product.id(), c.qty, c.discount));
        return l;
    }

    private BigDecimal cartDiscountAmount() {
        String t = cartDiscount.getText();
        if (t == null || t.isBlank()) return BigDecimal.ZERO;
        BigDecimal v = Validate.decimal(t, "Discount");
        if (!discountIsPercent.isSelected()) return v;
        List<com.erolitechnologies.bizflow.domain.sale.CartLine> cl = new ArrayList<>();
        for (CartItem c : cart) cl.add(new com.erolitechnologies.bizflow.domain.sale.CartLine(c.product.id(), c.product.name(), c.qty, c.product.price(), c.product.cost(), c.product.taxExempt(), c.discount));
        return cl.isEmpty() ? BigDecimal.ZERO : SaleCalculator.percentToAmount(cl, v);
    }

    private SaleTotals current;

    private void recalc() {
        current = null;
        if (cart.isEmpty()) { subtotal.setText(Ui.money(BigDecimal.ZERO)); discountLbl.setText(Ui.money(BigDecimal.ZERO)); tax.setText(Ui.money(BigDecimal.ZERO)); total.setText(Ui.money(BigDecimal.ZERO)); return; }
        try {
            current = ctx.checkout.preview(s(), lines(), cartDiscountAmount());
            subtotal.setText(Ui.money(current.subtotal()));
            discountLbl.setText(Ui.money(current.discount()));
            tax.setText(Ui.money(current.tax()));
            total.setText(Ui.money(current.total()));
        } catch (RuntimeException e) {
            total.setText("-");
            if (e instanceof AppException) Ui.toast("Check the sale", e.getMessage());
        }
    }

    private void pay() {
        if (cart.isEmpty()) throw new AppException("Add at least one item first.");
        recalc();
        if (current == null) throw new AppException("This sale cannot be totalled. Fix the highlighted problem first.");
        BigDecimal due = current.total();
        List<String> methods = new ArrayList<>(List.of(ctx.settings.get("payment_methods").split("\\s*,\\s*")));
        CustomerService.Customer cust = customer.getValue();
        if (cust != null && cust.creditEnabled()) methods.add(PaymentAllocator.CREDIT);

        VBox rows = new VBox(8);
        List<ComboBox<String>> methodBoxes = new ArrayList<>();
        List<TextField> amounts = new ArrayList<>();
        List<TextField> refs = new ArrayList<>();
        Runnable[] addRow = new Runnable[1];
        addRow[0] = () -> {
            ComboBox<String> m = new ComboBox<>();
            m.getItems().addAll(methods);
            m.setValue(methodBoxes.isEmpty() ? methods.get(0) : methods.stream().filter(x -> !x.equals("Cash")).findFirst().orElse(methods.get(0)));
            TextField a = Ui.field("Amount");
            a.setText(methodBoxes.isEmpty() ? Ui.plain(due).replace(",", "") : "");
            TextField r = Ui.field("Reference (optional)");
            methodBoxes.add(m); amounts.add(a); refs.add(r);
            rows.getChildren().add(Ui.row(8, m, a, r));
        };
        addRow[0].run();
        Button split = Ui.button("+ Split payment", () -> addRow[0].run());
        Label dueLbl = new Label("Amount due: " + Ui.money(due));
        dueLbl.getStyleClass().add("totals-big");
        VBox box = new VBox(12, dueLbl, rows, split, Ui.muted("Change is only given from the cash portion."));
        box.setPrefWidth(560);

        CheckoutService.Result[] done = new CheckoutService.Result[1];
        boolean ok = Ui.dialog("Take payment", box, "Complete sale", () -> {
            List<PaymentLine> pays = new ArrayList<>();
            for (int i = 0; i < methodBoxes.size(); i++) {
                String a = amounts.get(i).getText();
                if (a == null || a.isBlank()) continue;
                pays.add(new PaymentLine(methodBoxes.get(i).getValue(), Validate.decimal(a, "Payment amount"), refs.get(i).getText()));
            }
            done[0] = ctx.checkout.checkout(s(), new CheckoutService.Request(lines(), cartDiscountAmount(), cust == null ? null : cust.id(), pays));
        });
        if (ok && done[0] != null) afterSale(done[0]);
    }

    private void afterSale(CheckoutService.Result r) {
        cart.clear();
        cartDiscount.clear();
        customer.setValue(null);
        recalc();
        results.getItems().clear();
        List<String> receipt = ctx.receipts.render(s(), r.saleId(), false);
        TextArea ta = new TextArea(String.join("\n", receipt));
        ta.setEditable(false);
        ta.setStyle("-fx-font-family: 'Consolas', monospace;");
        ta.setPrefSize(440, 460);
        Label change = new Label(r.change().signum() > 0 ? "Change due: " + Ui.money(r.change()) : "Paid in full");
        change.getStyleClass().add("totals-big");
        VBox box = new VBox(10, change, ta, Ui.button("Print receipt", () -> PrintHelper.printReceipt(ctx, receipt, app.stage())));
        Ui.dialog("Sale complete - " + r.receiptNo(), box, "New sale", () -> { });
        search.requestFocus();
    }
}
