package com.erolitechnologies.bizflow.ui;

import com.erolitechnologies.bizflow.App;
import com.erolitechnologies.bizflow.AppContext;
import com.erolitechnologies.bizflow.service.DashboardService;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.time.LocalDate;

/** Every figure comes from the database through DashboardService for the chosen period. */
public final class DashboardView extends View {
    private final VBox body = new VBox(14);
    private final ComboBox<DashboardService.Range> range = new ComboBox<>();
    private final DatePicker from = new DatePicker(LocalDate.now().minusDays(7));
    private final DatePicker to = new DatePicker(LocalDate.now());

    public DashboardView(AppContext ctx, App app) { super(ctx, app); }

    @Override
    public Node build() {
        range.getItems().addAll(DashboardService.Range.values());
        range.setValue(DashboardService.Range.TODAY);
        range.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(DashboardService.Range r) {
                return r == null ? "" : switch (r) { case TODAY -> "Today"; case YESTERDAY -> "Yesterday"; case THIS_WEEK -> "This week"; case THIS_MONTH -> "This month"; case CUSTOM -> "Custom range"; };
            }
            @Override public DashboardService.Range fromString(String s) { return null; }
        });
        range.setOnAction(e -> { boolean c = range.getValue() == DashboardService.Range.CUSTOM; from.setDisable(!c); to.setDisable(!c); refresh(); });
        from.setDisable(true);
        to.setDisable(true);
        from.setOnAction(e -> refresh());
        to.setOnAction(e -> refresh());
        HBox filters = Ui.row(10, new Label("Period:"), range, new Label("From"), from, new Label("To"), to, Ui.spacer(), Ui.button("Refresh", this::refresh));
        ScrollPane sp = new ScrollPane(body);
        sp.setFitToWidth(true);
        sp.getStyleClass().add("flat-scroll");
        VBox page = Ui.page("Dashboard", filters, sp);
        VBox.setVgrow(sp, Priority.ALWAYS);
        refresh();
        return page;
    }

    private void refresh() {
        Ui.guard(() -> {
            DashboardService.Data d = ctx.dashboard.load(s(), range.getValue(), from.getValue(), to.getValue());
            body.getChildren().clear();
            HBox k1 = Ui.row(12, Ui.kpi("Revenue (excl. tax)", Ui.money(d.revenue()), null), Ui.kpi("Total sales (incl. tax)", Ui.money(d.totalSales()), null),
                    Ui.kpi("Transactions", String.valueOf(d.transactions()), null), Ui.kpi("Est. gross profit", Ui.money(d.grossProfit()), null));
            HBox k2 = Ui.row(12, Ui.kpi("Expenses", Ui.money(d.expenses()), null), Ui.kpi("Net profit", Ui.money(d.netProfit()), d.netProfit().signum() < 0 ? "kpi-bad" : "kpi-good"),
                    Ui.kpi("Products", String.valueOf(d.products()), null), Ui.kpi("Low stock", String.valueOf(d.lowStock()), d.lowStock() > 0 ? "kpi-bad" : null));
            body.getChildren().addAll(Ui.muted("Showing " + d.from() + " to " + d.to()), k1, k2);

            CategoryAxis x = new CategoryAxis();
            NumberAxis y = new NumberAxis();
            LineChart<String, Number> trend = new LineChart<>(x, y);
            trend.setTitle("Sales trend");
            trend.setLegendVisible(false);
            trend.setAnimated(false);
            XYChart.Series<String, Number> series = new XYChart.Series<>();
            for (var p : d.trend()) series.getData().add(new XYChart.Data<>(p.day(), p.revenue()));
            trend.getData().add(series);
            trend.setPrefHeight(280);

            PieChart pie = new PieChart(FXCollections.observableArrayList());
            pie.setTitle("Payment methods");
            pie.setAnimated(false);
            for (var m : d.methods()) pie.getData().add(new PieChart.Data(m.method() + " " + Ui.money(m.amount()), m.amount().doubleValue()));
            pie.setPrefHeight(280);
            HBox charts = Ui.row(12, Ui.card(trend), Ui.card(pie));
            HBox.setHgrow(charts.getChildren().get(0), Priority.ALWAYS);
            body.getChildren().add(charts);

            TableView<DashboardService.TopProduct> top = Ui.table("No sales in this period");
            Ui.col(top, "Product", DashboardService.TopProduct::name, 220);
            Ui.colRight(top, "Qty", t -> Ui.qty(t.qty()), 70);
            Ui.colRight(top, "Revenue", t -> Ui.money(t.revenue()), 100);
            top.getItems().addAll(d.top());
            top.setPrefHeight(240);
            TableView<DashboardService.Recent> rec = Ui.table("No transactions in this period");
            Ui.col(rec, "Receipt", DashboardService.Recent::receiptNo, 150);
            Ui.col(rec, "Time", r -> Ui.date(r.time()), 120);
            Ui.col(rec, "Cashier", DashboardService.Recent::cashier, 100);
            Ui.colRight(rec, "Total", r -> Ui.money(r.total()), 90);
            Ui.col(rec, "Status", DashboardService.Recent::status, 90);
            rec.getItems().addAll(d.recent());
            rec.setPrefHeight(240);
            HBox tables = Ui.row(12, Ui.card(Ui.h2("Top-selling products"), top), Ui.card(Ui.h2("Recent transactions"), rec));
            for (Node n : tables.getChildren()) HBox.setHgrow(n, Priority.ALWAYS);
            body.getChildren().add(tables);
            body.setPadding(new Insets(0, 4, 12, 0));
        });
    }
}
