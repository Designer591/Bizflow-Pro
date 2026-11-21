package com.erolitechnologies.bizflow.ui;

import com.erolitechnologies.bizflow.App;
import com.erolitechnologies.bizflow.AppContext;
import com.erolitechnologies.bizflow.infra.AppException;
import com.erolitechnologies.bizflow.security.Permission;
import com.erolitechnologies.bizflow.service.CustomerService;
import com.erolitechnologies.bizflow.service.Validate;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class CustomersView extends View {
    private final TableView<CustomerService.Customer> table = Ui.table("No customers found");
    private final TextField text = Ui.field("Search by name, phone or email");
    private final CheckBox inactive = new CheckBox("Show inactive");

    public CustomersView(AppContext ctx, App app) { super(ctx, app); }

    @Override
    public Node build() {
        boolean fin = can(Permission.CUSTOMER_FINANCE_VIEW);
        Ui.col(table, "Name", CustomerService.Customer::name, 200);
        Ui.col(table, "Phone", c -> c.phone() == null ? "" : c.phone(), 120);
        Ui.col(table, "Email", c -> c.email() == null ? "" : c.email(), 180);
        if (fin) {
            Ui.col(table, "Credit", c -> c.creditEnabled() ? "Limit " + Ui.money(c.creditLimit()) : "No", 120);
            Ui.colRight(table, "Balance owed", c -> Ui.money(c.balance()), 110);
        }
        Ui.col(table, "Status", c -> c.active() ? "Active" : "Inactive", 80);
        text.setOnAction(e -> load());
        inactive.setOnAction(e -> load());
        boolean manage = can(Permission.CUSTOMER_MANAGE);
        Button add = Ui.primary("New customer", () -> edit(null));
        Button edit = Ui.button("Edit", () -> edit(selected()));
        add.setDisable(!manage); edit.setDisable(!manage);
        Button credit = Ui.button("Credit account", this::credit);
        credit.setDisable(!manage || !fin);
        Button pay = Ui.button("Receive payment", this::receivePayment);
        pay.setDisable(!fin);
        Button stmt = Ui.button("Statement", this::statement);
        stmt.setDisable(!fin);
        Button hist = Ui.button("Purchase history", this::history);
        VBox page = Ui.page("Customers", Ui.row(8, text, inactive, Ui.primary("Search", this::load)), table, Ui.row(8, add, edit, credit, pay, stmt, hist));
        HBox.setHgrow(text, Priority.ALWAYS);
        load();
        return page;
    }

    private void load() { Ui.guard(() -> table.getItems().setAll(ctx.customers.search(s(), text.getText(), inactive.isSelected()))); }

    private CustomerService.Customer selected() {
        var c = table.getSelectionModel().getSelectedItem();
        if (c == null) throw new AppException("Select a customer first.");
        return c;
    }

    private void edit(CustomerService.Customer c) {
        TextField name = Ui.field("Full name"), phone = Ui.field("+263 ..."), email = Ui.field("Email"), address = Ui.field("Address");
        CheckBox active = new CheckBox("Active");
        active.setSelected(true);
        if (c != null) { name.setText(c.name()); phone.setText(c.phone() == null ? "" : c.phone()); email.setText(c.email() == null ? "" : c.email()); address.setText(c.address() == null ? "" : c.address()); active.setSelected(c.active()); }
        GridPane g = Ui.form();
        Ui.addRow(g, 0, "Name *", name);
        Ui.addRow(g, 1, "Phone", phone);
        Ui.addRow(g, 2, "Email", email);
        Ui.addRow(g, 3, "Address", address);
        if (c != null) Ui.addRow(g, 4, "", active);
        Ui.dialog(c == null ? "New customer" : "Edit customer", g, "Save", () -> {
            if (c == null) ctx.customers.create(s(), name.getText(), phone.getText(), email.getText(), address.getText(), false, BigDecimal.ZERO);
            else ctx.customers.update(s(), c.id(), name.getText(), phone.getText(), email.getText(), address.getText(), active.isSelected());
            load();
        });
    }

    private void credit() {
        var c = selected();
        CheckBox on = new CheckBox("Allow this customer to buy on credit");
        on.setSelected(c.creditEnabled());
        TextField limit = Ui.field("Credit limit");
        limit.setText(c.creditLimit().toPlainString());
        GridPane g = Ui.form();
        g.add(on, 0, 0, 2, 1);
        Ui.addRow(g, 1, "Credit limit", limit);
        Ui.dialog("Credit account - " + c.name(), g, "Save", () -> { ctx.customers.setCredit(s(), c.id(), on.isSelected(), Validate.decimal(limit.getText(), "Credit limit")); load(); });
    }

    private void receivePayment() {
        var c = selected();
        TextField amt = Ui.field("Amount");
        ComboBox<String> method = new ComboBox<>();
        method.getItems().addAll(List.of(ctx.settings.get("payment_methods").split("\\s*,\\s*")));
        method.setValue(method.getItems().get(0));
        TextField ref = Ui.field("Reference (optional)");
        GridPane g = Ui.form();
        Ui.addRow(g, 0, "Balance owed", new Label(Ui.money(c.balance())));
        Ui.addRow(g, 1, "Amount received", amt);
        Ui.addRow(g, 2, "Method", method);
        Ui.addRow(g, 3, "Reference", ref);
        Ui.dialog("Receive payment - " + c.name(), g, "Record payment", () -> { ctx.customers.receivePayment(s(), c.id(), Validate.decimal(amt.getText(), "Amount"), method.getValue(), ref.getText()); load(); });
    }

    private void statement() {
        var c = selected();
        DatePicker from = new DatePicker(LocalDate.now().minusMonths(3)), to = new DatePicker(LocalDate.now());
        TableView<CustomerService.StatementLine> t = Ui.table("No transactions in this period");
        Ui.col(t, "Date", l -> Ui.date(l.date()), 120);
        Ui.col(t, "Type", CustomerService.StatementLine::type, 100);
        Ui.col(t, "Reference", CustomerService.StatementLine::reference, 160);
        Ui.colRight(t, "Charges", l -> l.debit().signum() == 0 ? "" : Ui.money(l.debit()), 90);
        Ui.colRight(t, "Payments", l -> l.credit().signum() == 0 ? "" : Ui.money(l.credit()), 90);
        Ui.colRight(t, "Balance", l -> Ui.money(l.runningBalance()), 90);
        Runnable reload = () -> Ui.guard(() -> t.getItems().setAll(ctx.customers.statement(s(), c.id(), from.getValue().toString(), to.getValue().toString())));
        from.setOnAction(e -> reload.run());
        to.setOnAction(e -> reload.run());
        reload.run();
        t.setPrefSize(760, 380);
        Ui.dialog("Statement - " + c.name(), new VBox(10, Ui.row(8, new Label("From"), from, new Label("To"), to), t), "Close", () -> { });
    }

    private void history() {
        var c = selected();
        TableView<CustomerService.PurchaseHistory> t = Ui.table("No purchases yet");
        Ui.col(t, "Date", h -> Ui.date(h.date()), 120);
        Ui.col(t, "Receipt", CustomerService.PurchaseHistory::receiptNo, 170);
        Ui.colRight(t, "Total", h -> Ui.money(h.total()), 100);
        Ui.col(t, "Status", CustomerService.PurchaseHistory::status, 120);
        t.getItems().addAll(ctx.customers.purchaseHistory(s(), c.id()));
        t.setPrefSize(600, 380);
        Ui.dialog("Purchases - " + c.name(), t, "Close", () -> { });
    }
}
