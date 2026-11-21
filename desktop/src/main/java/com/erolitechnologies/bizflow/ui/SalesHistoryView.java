package com.erolitechnologies.bizflow.ui;

import com.erolitechnologies.bizflow.App;
import com.erolitechnologies.bizflow.AppContext;
import com.erolitechnologies.bizflow.infra.AppException;
import com.erolitechnologies.bizflow.security.Permission;
import com.erolitechnologies.bizflow.service.SalesHistoryService;
import com.erolitechnologies.bizflow.service.Validate;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SalesHistoryView extends View {
    private final TableView<SalesHistoryService.SaleRow> table = Ui.table("No sales found for this search");
    private final TextField text = Ui.field("Receipt number, cashier or customer");
    private final DatePicker from = new DatePicker(LocalDate.now().minusDays(30));
    private final DatePicker to = new DatePicker(LocalDate.now());

    public SalesHistoryView(AppContext ctx, App app) { super(ctx, app); }

    @Override
    public Node build() {
        Ui.col(table, "Receipt", SalesHistoryService.SaleRow::receiptNo, 170);
        Ui.col(table, "Date / time", r -> Ui.date(r.when()), 130);
        Ui.col(table, "Cashier", SalesHistoryService.SaleRow::cashier, 110);
        Ui.col(table, "Customer", SalesHistoryService.SaleRow::customer, 140);
        Ui.colRight(table, "Total", r -> Ui.money(r.total()), 100);
        Ui.col(table, "Status", SalesHistoryService.SaleRow::status, 130);
        text.setOnAction(e -> load());
        Button receipt = Ui.button("View / reprint receipt", this::showReceipt);
        Button refund = Ui.button("Refund...", this::refund);
        refund.setDisable(!can(Permission.POS_REFUND));
        Button voidBtn = Ui.danger("Void sale...", this::voidSale);
        voidBtn.setDisable(!can(Permission.POS_VOID));
        HBox filters = Ui.row(8, text, new Label("From"), from, new Label("To"), to, Ui.primary("Search", this::load));
        HBox.setHgrow(text, Priority.ALWAYS);
        VBox page = Ui.page("Sales History", filters, table, Ui.row(8, receipt, refund, voidBtn, Ui.spacer(), Ui.muted(can(Permission.SALES_HISTORY_ALL) ? "Showing all cashiers" : "Showing your own sales")));
        load();
        return page;
    }

    private void load() {
        Ui.guard(() -> table.getItems().setAll(ctx.salesHistory.search(s(), text.getText(), from.getValue() == null ? null : from.getValue().toString(), to.getValue() == null ? null : to.getValue().toString())));
    }

    private SalesHistoryService.SaleRow selected() {
        var r = table.getSelectionModel().getSelectedItem();
        if (r == null) throw new AppException("Select a sale first.");
        return r;
    }

    private void showReceipt() {
        var r = selected();
        List<String> lines = ctx.receipts.render(s(), r.id(), true);
        TextArea ta = new TextArea(String.join("\n", lines));
        ta.setEditable(false);
        ta.setStyle("-fx-font-family: 'Consolas', monospace;");
        ta.setPrefSize(460, 480);
        VBox box = new VBox(10, ta, Ui.button("Print", () -> PrintHelper.printReceipt(ctx, lines, app.stage())));
        Ui.dialog("Receipt " + r.receiptNo(), box, "Close", () -> { });
    }

    private void refund() {
        var sale = selected();
        var items = ctx.salesHistory.items(s(), sale.id());
        GridPane g = Ui.form();
        Map<Long, TextField> qtys = new LinkedHashMap<>();
        int i = 0;
        g.add(new Label("Item (sold / already refunded)"), 0, i);
        g.add(new Label("Return qty"), 1, i++);
        for (var it : items) {
            TextField f = Ui.field("0");
            f.setPrefWidth(80);
            qtys.put(it.id(), f);
            g.add(new Label(it.description() + "  (" + Ui.qty(it.qty()) + " / " + Ui.qty(it.refundedQty()) + ")  " + Ui.money(it.lineTotal())), 0, i);
            g.add(f, 1, i++);
        }
        List<String> methods = new ArrayList<>(List.of(ctx.settings.get("payment_methods").split("\\s*,\\s*")));
        methods.add(com.erolitechnologies.bizflow.domain.sale.PaymentAllocator.CREDIT);
        ComboBox<String> method = new ComboBox<>();
        method.getItems().addAll(methods);
        method.setValue(methods.get(0));
        CheckBox restock = new CheckBox("Return the items to stock");
        restock.setSelected(true);
        TextField reason = Ui.field("Reason (required)");
        g.add(new Label("Refund paid as"), 0, i); g.add(method, 1, i++);
        g.add(restock, 0, i++, 2, 1);
        g.add(new Label("Reason"), 0, i); g.add(reason, 1, i);
        Ui.dialog("Refund " + sale.receiptNo(), g, "Refund", () -> {
            Map<Long, BigDecimal> q = new LinkedHashMap<>();
            for (var e : qtys.entrySet()) {
                String t = e.getValue().getText();
                if (t == null || t.isBlank()) continue;
                BigDecimal v = Validate.decimal(t, "Return quantity");
                if (v.signum() > 0) q.put(e.getKey(), v);
            }
            BigDecimal amount = ctx.refunds.refund(s(), sale.id(), q, method.getValue(), restock.isSelected(), reason.getText());
            Ui.info("Refund recorded: " + Ui.money(amount));
            load();
        });
    }

    private void voidSale() {
        var sale = selected();
        if (!Ui.confirm("Void sale " + sale.receiptNo() + "? Stock will be restored and payments reversed. The sale stays on record as VOIDED.")) return;
        Ui.prompt("Void sale", "Reason for voiding (required):", "").ifPresent(reason -> Ui.guard(() -> {
            ctx.refunds.voidSale(s(), sale.id(), reason);
            Ui.info("Sale voided.");
            load();
        }));
    }
}
