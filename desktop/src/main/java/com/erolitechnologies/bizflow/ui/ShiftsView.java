package com.erolitechnologies.bizflow.ui;

import com.erolitechnologies.bizflow.App;
import com.erolitechnologies.bizflow.AppContext;
import com.erolitechnologies.bizflow.security.Permission;
import com.erolitechnologies.bizflow.service.ShiftService;
import com.erolitechnologies.bizflow.service.Validate;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Open / close the cash drawer and reconcile. Managers see every cashier's shifts. */
public final class ShiftsView extends View {
    private final VBox current = new VBox(10);
    private final TableView<ShiftService.Shift> table = Ui.table("No shifts in this period");
    private final DatePicker from = new DatePicker(LocalDate.now().minusDays(14));
    private final DatePicker to = new DatePicker(LocalDate.now());

    public ShiftsView(AppContext ctx, App app) { super(ctx, app); }

    @Override
    public Node build() {
        Ui.col(table, "Cashier", ShiftService.Shift::cashier, 130);
        Ui.col(table, "Opened", x -> Ui.date(x.openedAt()), 120);
        Ui.col(table, "Closed", x -> Ui.date(x.closedAt()), 120);
        Ui.colRight(table, "Opening", x -> Ui.money(x.opening()), 90);
        Ui.colRight(table, "Expected", x -> Ui.money(x.expected()), 90);
        Ui.colRight(table, "Counted", x -> Ui.money(x.counted()), 90);
        Ui.colRight(table, "Variance", x -> Ui.money(x.variance()), 90);
        Ui.col(table, "Status", ShiftService.Shift::status, 80);
        from.setOnAction(e -> reload());
        to.setOnAction(e -> reload());
        VBox cur = new VBox(8, Ui.h2("My cash drawer"), current);
        cur.getStyleClass().add("card");
        VBox page = Ui.page("Cashier Shifts", cur, Ui.row(10, new Label("History from"), from, new Label("to"), to), table);
        reload();
        return page;
    }

    private void reload() {
        Ui.guard(() -> {
            current.getChildren().clear();
            var sh = ctx.shifts.currentShift(s());
            if (sh == null) {
                current.getChildren().addAll(Ui.muted("You have no open shift."), Ui.primary("Open shift", this::open));
            } else {
                BigDecimal exp = ctx.shifts.expectedNow(s());
                current.getChildren().addAll(Ui.muted("Open since " + Ui.date(sh.openedAt()) + ". Opening float " + Ui.money(sh.opening()) + ". Cash that should be in the drawer now: " + Ui.money(exp)),
                        Ui.primary("Close shift and count cash", this::close));
            }
            table.getItems().setAll(ctx.shifts.list(s(), from.getValue().toString(), to.getValue().toString()));
        });
    }

    private void open() {
        TextField f = Ui.field("0.00");
        GridPane g = Ui.form();
        Ui.addRow(g, 0, "Opening cash (float)", f);
        Ui.dialog("Open shift", g, "Open", () -> { ctx.shifts.open(s(), f.getText().isBlank() ? BigDecimal.ZERO : Validate.decimal(f.getText(), "Opening cash")); reload(); });
    }

    private void close() {
        TextField counted = Ui.field("Cash counted in the drawer");
        TextField notes = Ui.field("Notes (optional)");
        GridPane g = Ui.form();
        Ui.addRow(g, 0, "Counted cash *", counted);
        Ui.addRow(g, 1, "Notes", notes);
        Ui.dialog("Close shift", g, "Close shift", () -> {
            var r = ctx.shifts.close(s(), Validate.decimal(counted.getText(), "Counted cash"), notes.getText());
            Ui.info("Shift closed.\nExpected " + Ui.money(r.expected()) + ", counted " + Ui.money(r.counted()) + ", difference " + Ui.money(r.variance()) + ".");
            reload();
        });
    }
}
