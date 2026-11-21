package com.erolitechnologies.bizflow.ui;

import com.erolitechnologies.bizflow.App;
import com.erolitechnologies.bizflow.AppContext;
import com.erolitechnologies.bizflow.infra.AppException;
import com.erolitechnologies.bizflow.security.Permission;
import com.erolitechnologies.bizflow.service.ExpenseService;
import com.erolitechnologies.bizflow.service.FinanceService;
import com.erolitechnologies.bizflow.service.Validate;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.time.LocalDate;
import java.util.List;

/** Revenue, gross profit, net profit and cash received are shown separately on purpose; they are different numbers. */
public final class FinanceView extends View {
    private final DatePicker from = new DatePicker(LocalDate.now().withDayOfMonth(1));
    private final DatePicker to = new DatePicker(LocalDate.now());
    private final VBox summary = new VBox(12);
    private final TableView<ExpenseService.Expense> expenses = Ui.table("No expenses in this period");

    public FinanceView(AppContext ctx, App app) { super(ctx, app); }

    @Override
    public Node build() {
        from.setOnAction(e -> reload());
        to.setOnAction(e -> reload());
        Ui.col(expenses, "Date", ExpenseService.Expense::date, 100);
        Ui.col(expenses, "Category", ExpenseService.Expense::category, 130);
        Ui.col(expenses, "Description", ExpenseService.Expense::description, 220);
        Ui.col(expenses, "Method", ExpenseService.Expense::method, 90);
        Ui.colRight(expenses, "Amount", x -> Ui.money(x.amount()), 100);
        Ui.col(expenses, "By", ExpenseService.Expense::by, 90);
        boolean em = can(Permission.EXPENSE_MANAGE);
        Button add = Ui.primary("Record expense", this::addExpense);
        add.setDisable(!em);
        Button del = Ui.danger("Delete expense", this::deleteExpense);
        del.setDisable(!em);
        HBox filters = Ui.row(10, new Label("From"), from, new Label("To"), to, Ui.button("Refresh", this::reload));
        VBox exp = new VBox(8, Ui.h2("Expenses"), expenses, Ui.row(8, add, del));
        exp.getStyleClass().add("card");
        VBox.setVgrow(expenses, Priority.ALWAYS);
        VBox.setVgrow(exp, Priority.ALWAYS);
        VBox page = Ui.page("Finance", filters, summary, exp);
        reload();
        return page;
    }

    private void reload() {
        Ui.guard(() -> {
            String f = from.getValue().toString(), t = to.getValue().toString();
            FinanceService.Summary x = ctx.finance.summary(s(), f, t);
            summary.getChildren().setAll(
                    Ui.row(12, Ui.kpi("Revenue (net of refunds, excl. tax)", Ui.money(x.revenue()), null), Ui.kpi("Cost of goods sold", Ui.money(x.cogs()), null),
                            Ui.kpi("Gross profit", Ui.money(x.grossProfit()), null), Ui.kpi("Expenses", Ui.money(x.expenses()), null),
                            Ui.kpi("Net profit", Ui.money(x.netProfit()), x.netProfit().signum() < 0 ? "kpi-bad" : "kpi-good")),
                    Ui.row(12, Ui.kpi("Cash received", Ui.money(x.cashReceived()), null), Ui.kpi("Tax collected", Ui.money(x.taxCollected()), null),
                            Ui.kpi("Refunds", Ui.money(x.refunds()), null), Ui.kpi("Owed by customers", Ui.money(x.receivables()), null),
                            Ui.kpi("Owed to suppliers", Ui.money(x.payables()), null)),
                    Ui.muted("Revenue is sales excluding tax less refunds. Gross profit = revenue - cost of goods sold. Net profit = gross profit - expenses. "
                            + "Cash received is money actually collected (credit sales are not cash until the customer pays)."));
            if (can(Permission.EXPENSE_MANAGE)) expenses.getItems().setAll(ctx.expenses.list(s(), f, t));
        });
    }

    private void addExpense() {
        ComboBox<String> cat = new ComboBox<>();
        cat.setEditable(true);
        cat.getItems().addAll(ctx.expenses.categories());
        cat.setValue(cat.getItems().isEmpty() ? "Other" : cat.getItems().get(0));
        TextField desc = Ui.field("What was it for?"), amt = Ui.field("Amount");
        ComboBox<String> method = new ComboBox<>();
        method.getItems().addAll(List.of(ctx.settings.get("payment_methods").split("\\s*,\\s*")));
        method.setValue(method.getItems().get(0));
        DatePicker date = new DatePicker(LocalDate.now());
        GridPane g = Ui.form();
        Ui.addRow(g, 0, "Category", cat); Ui.addRow(g, 1, "Description", desc); Ui.addRow(g, 2, "Amount *", amt);
        Ui.addRow(g, 3, "Paid by", method); Ui.addRow(g, 4, "Date", date);
        Ui.dialog("Record expense", g, "Save", () -> {
            ctx.expenses.add(s(), cat.getEditor().getText() == null || cat.getEditor().getText().isBlank() ? cat.getValue() : cat.getEditor().getText(), desc.getText(),
                    Validate.positive(Validate.decimal(amt.getText(), "Amount"), "Amount"), method.getValue(), date.getValue());
            reload();
        });
    }

    private void deleteExpense() {
        var x = expenses.getSelectionModel().getSelectedItem();
        if (x == null) throw new AppException("Select an expense first.");
        Ui.prompt("Delete expense", "Reason for deleting this expense (kept in the audit log):", "").ifPresent(r -> Ui.guard(() -> { ctx.expenses.delete(s(), x.id(), r); reload(); }));
    }
}
