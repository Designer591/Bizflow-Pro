package com.erolitechnologies.bizflow.ui;

import com.erolitechnologies.bizflow.App;
import com.erolitechnologies.bizflow.infra.AppException;
import com.erolitechnologies.bizflow.infra.Log;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.File;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Reusable UI building blocks: dialogs, error handling, tables, form layout, formatting. */
public final class Ui {
    private static String currencySymbol = "$";
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");

    private Ui() {}

    // ---------- formatting ----------
    public static void setCurrency(String symbol) { currencySymbol = symbol == null || symbol.isBlank() ? "$" : symbol; }
    public static String money(BigDecimal v) { return v == null ? "-" : (v.signum() < 0 ? "-" : "") + currencySymbol + MONEY.format(v.abs()); }
    public static String plain(BigDecimal v) { return v == null ? "" : MONEY.format(v); }
    public static String qty(BigDecimal v) { return v == null ? "" : v.stripTrailingZeros().toPlainString(); }
    public static String date(String ts) { return ts == null || ts.length() < 10 ? "" : ts.substring(0, 16 > ts.length() ? ts.length() : 16); }

    // ---------- messages ----------
    public static void error(Throwable t) {
        String msg;
        if (t instanceof AppException) msg = t.getMessage();
        else {
            Log.error("Unexpected error", t);
            msg = "Something went wrong. Your data is safe. If this keeps happening, please contact support and mention the time it happened (details are in the application log).";
        }
        Alert a = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
        a.setTitle("BizFlow Pro");
        a.setHeaderText(null);
        a.showAndWait();
    }

    public static void fatal(String message) {
        Alert a = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        a.setHeaderText("BizFlow Pro cannot start");
        a.showAndWait();
    }

    public static void info(String message) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        a.setTitle("BizFlow Pro");
        a.setHeaderText(null);
        a.showAndWait();
    }

    public static boolean confirm(String message) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.YES, ButtonType.NO);
        a.setTitle("Please confirm");
        a.setHeaderText(null);
        return a.showAndWait().filter(b -> b == ButtonType.YES).isPresent();
    }

    public static Optional<String> prompt(String title, String message, String defaultValue) {
        TextInputDialog d = new TextInputDialog(defaultValue == null ? "" : defaultValue);
        d.setTitle(title);
        d.setHeaderText(null);
        d.setContentText(message);
        return d.showAndWait();
    }

    /** Small non-blocking notification (ControlsFX). Falls back silently if the toolkit cannot show it. */
    public static void toast(String title, String text) {
        try {
            org.controlsfx.control.Notifications.create().title(title).text(text).hideAfter(javafx.util.Duration.seconds(6)).showInformation();
        } catch (RuntimeException e) {
            Log.warn("Notification not shown: " + title);
        }
    }

    /** Run an action; any failure becomes a friendly dialog. */
    public static void guard(Runnable action) {
        try { action.run(); } catch (RuntimeException e) { error(e); }
    }

    /** Run slow work off the UI thread (exports, backups). */
    public static <T> void background(Supplier<T> work, Consumer<T> onSuccess) {
        Task<T> task = new Task<>() { @Override protected T call() { return work.get(); } };
        task.setOnSucceeded(e -> onSuccess.accept(task.getValue()));
        task.setOnFailed(e -> error(task.getException()));
        Thread t = new Thread(task, "bizflow-worker");
        t.setDaemon(true);
        t.start();
    }

    // ---------- building blocks ----------
    public static Node icon(String literal) {
        try { FontIcon f = new FontIcon(literal); f.setIconSize(18); return f; } catch (RuntimeException e) { return null; }
    }

    public static Label h1(String t) { Label l = new Label(t); l.getStyleClass().add("h1"); return l; }
    public static Label h2(String t) { Label l = new Label(t); l.getStyleClass().add("h2"); return l; }
    public static Label muted(String t) { Label l = new Label(t); l.getStyleClass().add("muted"); l.setWrapText(true); return l; }

    public static Button button(String text, Runnable action) {
        Button b = new Button(text);
        b.setOnAction(e -> guard(action));
        return b;
    }

    public static Button primary(String text, Runnable action) {
        Button b = button(text, action);
        b.getStyleClass().add("primary");
        return b;
    }

    public static Button danger(String text, Runnable action) {
        Button b = button(text, action);
        b.getStyleClass().add("danger");
        return b;
    }

    public static VBox card(Node... children) {
        VBox v = new VBox(10, children);
        v.getStyleClass().add("card");
        return v;
    }

    public static HBox row(double gap, Node... children) {
        HBox h = new HBox(gap, children);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    public static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        VBox.setVgrow(r, Priority.ALWAYS);
        return r;
    }

    public static VBox page(String title, Node... children) {
        VBox v = new VBox(14);
        v.setPadding(new Insets(22));
        Label t = h1(title);
        v.getChildren().add(t);
        v.getChildren().addAll(children);
        return v;
    }

    public static VBox kpi(String title, String value, String styleClass) {
        Label t = new Label(title); t.getStyleClass().add("kpi-title");
        Label v = new Label(value); v.getStyleClass().add("kpi-value");
        VBox box = card(t, v);
        box.setMinWidth(150);
        if (styleClass != null) box.getStyleClass().add(styleClass);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    public static TextField field(String prompt) { TextField f = new TextField(); f.setPromptText(prompt); return f; }

    public static <T> TableColumn<T, String> col(TableView<T> table, String title, Function<T, String> getter, double width) {
        TableColumn<T, String> c = new TableColumn<>(title);
        c.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(getter.apply(cd.getValue())));
        c.setPrefWidth(width);
        table.getColumns().add(c);
        return c;
    }

    public static <T> TableColumn<T, String> colRight(TableView<T> table, String title, Function<T, String> getter, double width) {
        TableColumn<T, String> c = col(table, title, getter, width);
        c.setStyle("-fx-alignment: CENTER-RIGHT;");
        return c;
    }

    public static <T> TableView<T> table(String placeholder) {
        TableView<T> t = new TableView<>();
        t.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        t.setPlaceholder(new Label(placeholder));
        VBox.setVgrow(t, Priority.ALWAYS);
        return t;
    }

    // ---------- forms & dialogs ----------
    public static GridPane form() {
        GridPane g = new GridPane();
        g.setHgap(12);
        g.setVgap(10);
        g.setPadding(new Insets(8));
        ColumnConstraints a = new ColumnConstraints(); a.setMinWidth(130);
        ColumnConstraints b = new ColumnConstraints(); b.setHgrow(Priority.ALWAYS); b.setMinWidth(240);
        g.getColumnConstraints().addAll(a, b);
        return g;
    }

    public static void addRow(GridPane g, int row, String label, Node input) {
        Label l = new Label(label);
        g.add(l, 0, row);
        g.add(input, 1, row);
        GridPane.setHgrow(input, Priority.ALWAYS);
        if (input instanceof Region r) r.setMaxWidth(Double.MAX_VALUE);
    }

    /**
     * Modal form. onOk may throw AppException: the message is shown and the dialog STAYS OPEN so users can correct input.
     * @return true if the dialog was confirmed and onOk succeeded
     */
    public static boolean dialog(String title, Node content, String okText, Runnable onOk) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle(title);
        d.setHeaderText(null);
        d.initOwner(App.get() == null ? null : App.get().stage());
        d.getDialogPane().setContent(content);
        d.getDialogPane().getStylesheets().add(App.class.getResource("/css/bizflow.css").toExternalForm());
        if (App.get() != null && "dark".equalsIgnoreCase(App.get().ctx().settings.get("theme"))) d.getDialogPane().getStyleClass().add("dark");
        ButtonType ok = new ButtonType(okText, ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);
        Node okBtn = d.getDialogPane().lookupButton(ok);
        okBtn.getStyleClass().add("primary");
        boolean[] done = {false};
        okBtn.addEventFilter(ActionEvent.ACTION, ev -> {
            try { onOk.run(); done[0] = true; }
            catch (RuntimeException ex) { error(ex); ev.consume(); }
        });
        d.showAndWait();
        return done[0];
    }

    public static <T> ComboBox<T> combo(List<T> items, Function<T, String> label) {
        ComboBox<T> c = new ComboBox<>();
        c.getItems().addAll(items);
        c.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(T o) { return o == null ? "" : label.apply(o); }
            @Override public T fromString(String s) { return null; }
        });
        c.setMaxWidth(Double.MAX_VALUE);
        return c;
    }

    public static File chooseSave(Window owner, String title, String initialName, String desc, String... extensions) {
        FileChooser fc = new FileChooser();
        fc.setTitle(title);
        fc.setInitialFileName(initialName);
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(desc, extensions));
        return fc.showSaveDialog(owner);
    }

    public static File chooseOpen(Window owner, String title, String desc, String... extensions) {
        FileChooser fc = new FileChooser();
        fc.setTitle(title);
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(desc, extensions));
        return fc.showOpenDialog(owner);
    }

    /** Opens the system print dialog and prints monospaced receipt text. */
    public static void printText(List<String> lines, Window owner) {
        javafx.print.PrinterJob job = javafx.print.PrinterJob.createPrinterJob();
        if (job == null) { info("No printer is available. Install or connect a printer and try again."); return; }
        if (!job.showPrintDialog(owner)) return;
        javafx.scene.text.Text text = new javafx.scene.text.Text(String.join("\n", lines));
        text.setFont(javafx.scene.text.Font.font("Consolas", 9));
        if (job.printPage(text)) job.endJob(); else info("The receipt could not be printed. Check the printer and try again.");
    }

    public static void runLater(Runnable r) { Platform.runLater(r); }
}
