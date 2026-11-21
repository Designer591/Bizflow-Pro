package com.erolitechnologies.bizflow.ui;

import com.erolitechnologies.bizflow.App;
import com.erolitechnologies.bizflow.AppContext;
import com.erolitechnologies.bizflow.infra.AppException;
import com.erolitechnologies.bizflow.security.Permission;
import com.erolitechnologies.bizflow.service.ProductService;
import com.erolitechnologies.bizflow.service.PurchaseService;
import com.erolitechnologies.bizflow.service.SupplierService;
import com.erolitechnologies.bizflow.service.Validate;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Suppliers and purchase orders / goods received. Receiving a PO is what increases stock. */
public final class SuppliersView extends View {
    private final TableView<SupplierService.Supplier> suppliers = Ui.table("No suppliers yet");
    private final TableView<PurchaseService.Order> orders = Ui.table("No purchase orders");

    public SuppliersView(AppContext ctx, App app) { super(ctx, app); }

    @Override
    public Node build() {
        boolean fin = can(Permission.SUPPLIER_FINANCE_VIEW);
        Ui.col(suppliers, "Name", SupplierService.Supplier::name, 200);
        Ui.col(suppliers, "Contact", SupplierService.Supplier::contact, 130);
        Ui.col(suppliers, "Phone", SupplierService.Supplier::phone, 120);
        Ui.col(suppliers, "Email", SupplierService.Supplier::email, 170);
        if (fin) Ui.colRight(suppliers, "We owe", s -> Ui.money(s.balance()), 100);
        Ui.col(suppliers, "Status", s -> s.active() ? "Active" : "Inactive", 70);
        Button add = Ui.primary("New supplier", () -> editSupplier(null));
        Button edit = Ui.button("Edit", () -> editSupplier(selSupplier()));
        Button hist = Ui.button("Transaction history", this::supplierHistory);
        hist.setDisable(!fin);
        VBox supTab = new VBox(10, suppliers, Ui.row(8, add, edit, hist));
        supTab.setPadding(new javafx.geometry.Insets(12));

        Ui.col(orders, "PO number", PurchaseService.Order::poNumber, 130);
        Ui.col(orders, "Supplier", PurchaseService.Order::supplier, 170);
        Ui.col(orders, "Status", PurchaseService.Order::status, 90);
        Ui.col(orders, "Invoice", PurchaseService.Order::invoiceNumber, 100);
        Ui.colRight(orders, "Total", o -> Ui.money(o.total()), 90);
        if (fin) Ui.colRight(orders, "Paid", o -> Ui.money(o.paid()), 90);
        Ui.col(orders, "Ordered", o -> Ui.date(o.orderedAt()), 120);
        boolean pm = can(Permission.PURCHASE_MANAGE);
        Button newPo = Ui.primary("New purchase order", this::newOrder);
        Button receive = Ui.button("Receive goods", this::receive);
        Button cancel = Ui.danger("Cancel order", this::cancelOrder);
        Button pay = Ui.button("Pay supplier", this::pay);
        newPo.setDisable(!pm); receive.setDisable(!pm); cancel.setDisable(!pm); pay.setDisable(!fin);
        VBox poTab = new VBox(10, orders, Ui.row(8, newPo, receive, pay, cancel));
        poTab.setPadding(new javafx.geometry.Insets(12));

        TabPane tabs = new TabPane(new Tab("Purchase orders", poTab), new Tab("Suppliers", supTab));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        VBox page = Ui.page("Suppliers & Purchasing", tabs);
        reload();
        return page;
    }

    private void reload() {
        Ui.guard(() -> {
            suppliers.getItems().setAll(ctx.suppliers.search(s(), null, true));
            orders.getItems().setAll(ctx.purchases.list(s(), null));
        });
    }

    private SupplierService.Supplier selSupplier() {
        var x = suppliers.getSelectionModel().getSelectedItem();
        if (x == null) throw new AppException("Select a supplier first.");
        return x;
    }

    private PurchaseService.Order selOrder() {
        var x = orders.getSelectionModel().getSelectedItem();
        if (x == null) throw new AppException("Select a purchase order first.");
        return x;
    }

    private void editSupplier(SupplierService.Supplier sp) {
        TextField name = Ui.field("Company name"), contact = Ui.field("Contact person"), phone = Ui.field("Phone"), email = Ui.field("Email"), addr = Ui.field("Address");
        CheckBox active = new CheckBox("Active");
        active.setSelected(true);
        if (sp != null) { name.setText(sp.name()); contact.setText(sp.contact()); phone.setText(sp.phone()); email.setText(sp.email()); addr.setText(sp.address()); active.setSelected(sp.active()); }
        GridPane g = Ui.form();
        Ui.addRow(g, 0, "Name *", name); Ui.addRow(g, 1, "Contact", contact); Ui.addRow(g, 2, "Phone", phone);
        Ui.addRow(g, 3, "Email", email); Ui.addRow(g, 4, "Address", addr);
        if (sp != null) Ui.addRow(g, 5, "", active);
        Ui.dialog(sp == null ? "New supplier" : "Edit supplier", g, "Save", () -> {
            if (sp == null) ctx.suppliers.create(s(), name.getText(), contact.getText(), phone.getText(), email.getText(), addr.getText());
            else ctx.suppliers.update(s(), sp.id(), name.getText(), contact.getText(), phone.getText(), email.getText(), addr.getText(), active.isSelected());
            reload();
        });
    }

    private void supplierHistory() {
        var sp = selSupplier();
        TableView<SupplierService.Transaction> t = Ui.table("No transactions");
        Ui.col(t, "Date", x -> Ui.date(x.date()), 120);
        Ui.col(t, "Type", SupplierService.Transaction::type, 120);
        Ui.col(t, "Reference", SupplierService.Transaction::reference, 160);
        Ui.colRight(t, "Amount", x -> Ui.money(x.amount()), 100);
        t.getItems().addAll(ctx.suppliers.history(s(), sp.id()));
        t.setPrefSize(620, 360);
        Ui.dialog("History - " + sp.name(), t, "Close", () -> { });
    }

    private void newOrder() {
        var sups = ctx.suppliers.search(s(), null, false);
        if (sups.isEmpty()) throw new AppException("Add a supplier first.");
        ComboBox<SupplierService.Supplier> sup = Ui.combo(sups, SupplierService.Supplier::name);
        sup.setValue(sups.get(0));
        TextField invoice = Ui.field("Supplier invoice number (optional)");
        List<ProductService.Product> prods = ctx.products.search(s(), null, null, false, false);
        ComboBox<ProductService.Product> prod = Ui.combo(prods, ProductService.Product::name);
        TextField qty = Ui.field("Qty"), cost = Ui.field("Unit cost");
        TableView<PurchaseService.OrderLine> lines = Ui.table("Add the products you are ordering");
        Map<Long, String> names = new LinkedHashMap<>();
        for (var p : prods) names.put(p.id(), p.name());
        Ui.col(lines, "Product", l -> names.getOrDefault(l.productId(), "#" + l.productId()), 230);
        Ui.colRight(lines, "Qty", l -> Ui.qty(l.qty()), 70);
        Ui.colRight(lines, "Unit cost", l -> Ui.money(l.unitCost()), 90);
        prod.setOnAction(e -> { if (prod.getValue() != null) cost.setText(prod.getValue().cost().toPlainString()); });
        Button addLine = Ui.button("Add line", () -> {
            if (prod.getValue() == null) throw new AppException("Choose a product.");
            lines.getItems().add(new PurchaseService.OrderLine(prod.getValue().id(), Validate.positive(Validate.decimal(qty.getText(), "Quantity"), "Quantity"), Validate.nonNegative(Validate.decimal(cost.getText(), "Unit cost"), "Unit cost")));
            qty.clear();
        });
        Button rem = Ui.button("Remove line", () -> { var l = lines.getSelectionModel().getSelectedItem(); if (l != null) lines.getItems().remove(l); });
        lines.setPrefHeight(180);
        VBox box = new VBox(10, Ui.row(8, new Label("Supplier"), sup, invoice), Ui.row(8, prod, qty, cost, addLine, rem), lines);
        box.setPrefWidth(720);
        Ui.dialog("New purchase order", box, "Create order", () -> {
            ctx.purchases.createOrder(s(), sup.getValue().id(), new ArrayList<>(lines.getItems()), invoice.getText());
            reload();
        });
    }

    private void receive() {
        var o = selOrder();
        var items = ctx.purchases.items(s(), o.id());
        GridPane g = Ui.form();
        Map<Long, TextField> fields = new LinkedHashMap<>();
        int r = 0;
        g.add(new Label("Product (ordered / already received)"), 0, r);
        g.add(new Label("Receive now"), 1, r++);
        for (var it : items) {
            TextField f = Ui.field("0");
            f.setText(Ui.qty(it.qty().subtract(it.received())));
            fields.put(it.id(), f);
            g.add(new Label(it.product() + "  (" + Ui.qty(it.qty()) + " / " + Ui.qty(it.received()) + ")"), 0, r);
            g.add(f, 1, r++);
        }
        TextField inv = Ui.field("Supplier invoice number");
        inv.setText(o.invoiceNumber() == null ? "" : o.invoiceNumber());
        g.add(new Label("Invoice number"), 0, r);
        g.add(inv, 1, r);
        Ui.dialog("Receive goods - " + o.poNumber(), g, "Receive and add to stock", () -> {
            Map<Long, BigDecimal> m = new LinkedHashMap<>();
            for (var e : fields.entrySet()) {
                String t = e.getValue().getText();
                if (t != null && !t.isBlank() && Validate.decimal(t, "Quantity").signum() != 0) m.put(e.getKey(), Validate.decimal(t, "Quantity"));
            }
            ctx.purchases.receive(s(), o.id(), m, inv.getText());
            reload();
        });
    }

    private void cancelOrder() {
        var o = selOrder();
        Ui.prompt("Cancel order", "Reason for cancelling " + o.poNumber() + ":", "").ifPresent(r -> Ui.guard(() -> { ctx.purchases.cancel(s(), o.id(), r); reload(); }));
    }

    private void pay() {
        var o = selOrder();
        TextField amt = Ui.field("Amount");
        amt.setText(o.total().subtract(o.paid()).toPlainString());
        ComboBox<String> method = new ComboBox<>();
        method.getItems().addAll(List.of(ctx.settings.get("payment_methods").split("\\s*,\\s*")));
        method.setValue(method.getItems().get(0));
        TextField ref = Ui.field("Reference");
        GridPane g = Ui.form();
        Ui.addRow(g, 0, "Order total", new Label(Ui.money(o.total()) + "   (paid " + Ui.money(o.paid()) + ")"));
        Ui.addRow(g, 1, "Amount", amt);
        Ui.addRow(g, 2, "Method", method);
        Ui.addRow(g, 3, "Reference", ref);
        Ui.dialog("Pay supplier - " + o.supplier(), g, "Record payment", () -> { ctx.purchases.paySupplier(s(), o.id(), Validate.decimal(amt.getText(), "Amount"), method.getValue(), ref.getText()); reload(); });
    }
}
