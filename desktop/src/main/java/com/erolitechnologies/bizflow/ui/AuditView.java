package com.erolitechnologies.bizflow.ui;

import com.erolitechnologies.bizflow.App;
import com.erolitechnologies.bizflow.AppContext;
import com.erolitechnologies.bizflow.service.AuditService;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.time.LocalDate;

public final class AuditView extends View {
    private final TableView<AuditService.Entry> table = Ui.table("No matching entries");
    private final TextField text = Ui.field("Search user, action or details");
    private final DatePicker from = new DatePicker(LocalDate.now().minusDays(7));
    private final DatePicker to = new DatePicker(LocalDate.now());

    public AuditView(AppContext ctx, App app) { super(ctx, app); }

    @Override
    public Node build() {
        Ui.col(table, "When", e -> Ui.date(e.when()), 130);
        Ui.col(table, "User", AuditService.Entry::username, 100);
        Ui.col(table, "Action", AuditService.Entry::action, 170);
        Ui.col(table, "Item", e -> e.entity() + (e.entityId().isEmpty() ? "" : " #" + e.entityId()), 110);
        Ui.col(table, "Details", AuditService.Entry::details, 380);
        text.setOnAction(e -> load());
        HBox bar = Ui.row(8, text, new Label("From"), from, new Label("To"), to, Ui.primary("Search", this::load));
        HBox.setHgrow(text, Priority.ALWAYS);
        VBox page = Ui.page("Audit Log", bar, table);
        load();
        return page;
    }

    private void load() {
        Ui.guard(() -> table.getItems().setAll(ctx.audit.search(s(), text.getText(), from.getValue() == null ? null : from.getValue().toString(), to.getValue() == null ? null : to.getValue().toString(), 1000)));
    }
}
