package com.erolitechnologies.bizflow.ui;

import com.erolitechnologies.bizflow.App;
import com.erolitechnologies.bizflow.AppContext;
import com.erolitechnologies.bizflow.infra.AppException;
import com.erolitechnologies.bizflow.licensing.Edition;
import com.erolitechnologies.bizflow.report.Table;
import com.erolitechnologies.bizflow.security.Permission;
import com.erolitechnologies.bizflow.service.ReportService;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class ReportsView extends View {
    private final TableView<List<String>> view = Ui.table("Choose a report and press Run");
    private final ComboBox<ReportService.Type> type = Ui.combo(new ArrayList<ReportService.Type>(), t -> t.label);
    private final DatePicker from = new DatePicker(LocalDate.now().withDayOfMonth(1));
    private final DatePicker to = new DatePicker(LocalDate.now());
    private final Label heading = Ui.h2("");
    private Table current;

    public ReportsView(AppContext ctx, App app) { super(ctx, app); }

    @Override
    public Node build() {
        type.getItems().setAll(ctx.reports.availableTo(s()));
        if (!type.getItems().isEmpty()) type.setValue(type.getItems().get(0));
        boolean export = can(Permission.REPORT_EXPORT) && ctx.licence.hasFeature(Edition.Feature.EXPORTS);
        Button pdf = Ui.button("Export PDF", () -> export("pdf"));
        Button xls = Ui.button("Export Excel", () -> export("xlsx"));
        pdf.setDisable(!export);
        xls.setDisable(!export);
        HBox bar = Ui.row(8, new Label("Report"), type, new Label("From"), from, new Label("To"), to, Ui.primary("Run", this::run), Ui.spacer(), pdf, xls);
        VBox page = Ui.page("Reports", bar, heading, view);
        if (!export) page.getChildren().add(Ui.muted("PDF and Excel export is not available for your role or licence edition."));
        return page;
    }

    private void run() {
        if (type.getValue() == null) throw new AppException("Choose a report.");
        if (from.getValue() == null || to.getValue() == null || from.getValue().isAfter(to.getValue())) throw new AppException("Choose a valid date range.");
        current = ctx.reports.run(s(), type.getValue(), from.getValue().toString(), to.getValue().toString());
        heading.setText(current.title() + "   (" + from.getValue() + " to " + to.getValue() + ")");
        view.getColumns().clear();
        for (int i = 0; i < current.headers().size(); i++) {
            final int idx = i;
            TableColumn<List<String>, String> c = new TableColumn<>(current.headers().get(i));
            c.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(idx < cd.getValue().size() ? cd.getValue().get(idx) : ""));
            c.setPrefWidth(Math.max(90, 900.0 / current.headers().size()));
            if (current.numericColumns() != null && idx < current.numericColumns().size() && current.numericColumns().get(idx)) c.setStyle("-fx-alignment: CENTER-RIGHT;");
            view.getColumns().add(c);
        }
        List<List<String>> rows = new ArrayList<>(current.rows());
        if (current.totals() != null) rows.add(current.totals());
        view.getItems().setAll(rows);
    }

    private void export(String kind) {
        if (current == null) throw new AppException("Run a report first.");
        File f = Ui.chooseSave(app.stage(), "Save report", current.title().replaceAll("[^A-Za-z0-9]+", "-") + "." + kind, kind.equals("pdf") ? "PDF document" : "Excel workbook", "*." + kind);
        if (f == null) return;
        Table t = current;
        Ui.background(() -> {
            if (kind.equals("pdf")) ctx.exports.exportPdf(s(), t, f.toPath()); else ctx.exports.exportExcel(s(), t, f.toPath());
            return f;
        }, saved -> Ui.toast("Report saved", saved.getName()));
    }
}
