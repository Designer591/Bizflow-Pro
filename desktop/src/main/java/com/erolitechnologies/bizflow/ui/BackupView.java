package com.erolitechnologies.bizflow.ui;

import com.erolitechnologies.bizflow.App;
import com.erolitechnologies.bizflow.AppContext;
import com.erolitechnologies.bizflow.infra.AppException;
import com.erolitechnologies.bizflow.service.BackupService;
import com.erolitechnologies.bizflow.service.Validate;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;

/** Manual and scheduled backups, external-drive folders, validation, and restore (which always saves the current data first). */
public final class BackupView extends View {
    private final Label status = new Label();
    private final Label folder = new Label();
    private final TableView<BackupService.BackupFile> table = Ui.table("No backups found in the backup folder");

    public BackupView(AppContext ctx, App app) { super(ctx, app); }

    @Override
    public Node build() {
        Ui.col(table, "File", b -> b.path().getFileName().toString(), 300);
        Ui.col(table, "Date", b -> Ui.date(b.modified()), 120);
        Ui.colRight(table, "Size", b -> String.format("%.1f MB", b.sizeBytes() / 1048576.0), 80);
        Ui.col(table, "Check", b -> b.valid() ? "Verified" : "DAMAGED", 80);
        Ui.col(table, "Kind", b -> b.safetyCopy() ? "Safety copy before restore" : "Backup", 170);

        Button now = Ui.primary("Back up now", () -> Ui.background(() -> ctx.backup.backupNow(s()), p -> { Ui.toast("Backup complete", p.getFileName().toString()); refresh(); }));
        Button choose = Ui.button("Choose backup folder...", this::chooseFolder);
        Button schedule = Ui.button("Schedule...", this::schedule);
        Button restore = Ui.danger("Restore selected backup...", this::restoreSelected);
        Button restoreFile = Ui.button("Restore from a file...", this::restoreFromFile);
        VBox top = new VBox(8, Ui.h2("Backup status"), status, folder, Ui.row(8, now, choose, schedule));
        top.getStyleClass().add("card");
        VBox page = Ui.page("Backup & Restore", top, table, Ui.row(8, restore, restoreFile, Ui.spacer(),
                Ui.muted("Restoring always saves your current data first, so nothing is lost if you pick the wrong file.")));
        refresh();
        return page;
    }

    private void refresh() {
        Ui.guard(() -> {
            var st = ctx.backup.status();
            if (st.lastError() != null && !st.lastError().isBlank()) { status.setText("Last backup FAILED: " + st.lastError()); status.getStyleClass().setAll("error-text"); }
            else if (st.lastOk() == null || st.lastOk().isBlank()) { status.setText("No backup has been made yet."); status.getStyleClass().setAll("error-text"); }
            else { status.setText("Last successful backup: " + Ui.date(st.lastOk()) + (st.overdue() ? "  (overdue - back up now)" : "  (up to date)")); status.getStyleClass().setAll(st.overdue() ? "error-text" : "ok-text"); }
            folder.setText("Backup folder: " + ctx.backup.configuredDir());
            table.getItems().setAll(ctx.backup.list(s()));
        });
    }

    private void chooseFolder() {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Choose backup folder (an external drive is a good choice)");
        File f = dc.showDialog(app.stage());
        if (f == null) return;
        ctx.settings.update(s(), Map.of("backup_dir", f.getAbsolutePath()));
        refresh();
    }

    private void schedule() {
        CheckBox on = new CheckBox("Back up automatically while BizFlow Pro is open");
        on.setSelected(ctx.settings.getBool("backup_enabled"));
        TextField hours = Ui.field("24");
        hours.setText(ctx.settings.get("backup_interval_hours"));
        TextField keep = Ui.field("14");
        keep.setText(ctx.settings.get("backup_retention"));
        GridPane g = Ui.form();
        g.add(on, 0, 0, 2, 1);
        Ui.addRow(g, 1, "Every (hours)", hours);
        Ui.addRow(g, 2, "Keep the latest", keep);
        Ui.dialog("Scheduled backups", g, "Save", () -> {
            int h = Validate.decimal(hours.getText(), "Hours").intValue();
            int k = Validate.decimal(keep.getText(), "Backups to keep").intValue();
            if (h < 1 || h > 720) throw new AppException("Hours must be between 1 and 720.");
            if (k < 1 || k > 365) throw new AppException("Keep between 1 and 365 backups.");
            ctx.settings.update(s(), Map.of("backup_enabled", String.valueOf(on.isSelected()), "backup_interval_hours", String.valueOf(h), "backup_retention", String.valueOf(k)));
            refresh();
        });
    }

    private void restoreSelected() {
        var b = table.getSelectionModel().getSelectedItem();
        if (b == null) throw new AppException("Select a backup first.");
        doRestore(b.path());
    }

    private void restoreFromFile() {
        File f = Ui.chooseOpen(app.stage(), "Choose a backup file", "BizFlow backups", "*.db");
        if (f != null) doRestore(f.toPath());
    }

    private void doRestore(Path p) {
        if (!Ui.confirm("Restore from " + p.getFileName() + "?\n\nAll changes made after that backup will be replaced. Your current data is saved first as a safety copy. You will be signed out afterwards.")) return;
        ctx.backup.restore(s(), p);
        ctx.session = null;
        Ui.info("The backup was restored. Please sign in again.");
        app.showEntry();
    }
}
