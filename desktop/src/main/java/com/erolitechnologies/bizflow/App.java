package com.erolitechnologies.bizflow;

import com.erolitechnologies.bizflow.infra.AppPaths;
import com.erolitechnologies.bizflow.infra.Db;
import com.erolitechnologies.bizflow.infra.Log;
import com.erolitechnologies.bizflow.infra.Migrator;
import com.erolitechnologies.bizflow.service.RbacSeeder;
import com.erolitechnologies.bizflow.ui.*;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class App extends Application {
    private static App instance;
    private Stage stage;
    private AppContext ctx;
    private Scene scene;
    private ScheduledExecutorService scheduler;

    public static App get() { return instance; }

    @Override
    public void start(Stage primary) {
        instance = this;
        this.stage = primary;
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            Log.error("Unhandled error", e);
            Platform.runLater(() -> Ui.error(e));
        });
        try {
            AppPaths paths = AppPaths.forCurrentUser();
            Log.configureFile(paths.logDir());
            Db db = Db.sqlite(paths.dbFile());
            new Migrator(db).migrate();
            RbacSeeder.ensure(db);
            ctx = new AppContext(paths, db);
        } catch (RuntimeException e) {
            Log.error("Start-up failed", e);
            Ui.fatal("BizFlow Pro could not open its database.\n\n" + e.getMessage() + "\n\nYour data was not changed. Please contact support and send the log files from your BizFlowPro\\logs folder.");
            Platform.exit();
            return;
        }
        Ui.setCurrency(ctx.settings.get("currency_symbol"));
        stage.setTitle("BizFlow Pro");
        stage.setMinWidth(1100);
        stage.setMinHeight(700);
        try { stage.getIcons().add(new Image(App.class.getResourceAsStream("/icons/app.png"))); } catch (RuntimeException ignored) { }
        scene = new Scene(new javafx.scene.layout.StackPane(), 1320, 820);
        scene.getStylesheets().add(App.class.getResource("/css/bizflow.css").toExternalForm());
        stage.setScene(scene);
        stage.setMaximized(true);
        showEntry();
        stage.show();
        startBackupScheduler();
    }

    public AppContext ctx() { return ctx; }
    public Stage stage() { return stage; }

    /** Login screen, or the setup wizard on first launch. */
    public void showEntry() {
        ctx.session = null;
        setRoot(ctx.setup.isSetupComplete() ? new LoginView(ctx, this).build() : new SetupWizardView(ctx, this).build());
    }

    public void showMain() { setRoot(new MainShell(ctx, this).build()); }

    public void showLicenceGate() { setRoot(new LicenceGateView(ctx, this).build()); }

    public void setRoot(javafx.scene.Node node) {
        Parent root = node instanceof Parent p ? p : new javafx.scene.layout.StackPane(node);
        applyTheme(root);
        scene.setRoot(root);
    }

    public void applyTheme(Parent root) {
        boolean dark = "dark".equalsIgnoreCase(ctx.settings.get("theme"));
        scene.getRoot().getStyleClass().remove("dark");
        if (root != null) root.getStyleClass().remove("dark");
        if (dark) {
            scene.getRoot().getStyleClass().add("dark");
            if (root != null && !root.getStyleClass().contains("dark")) root.getStyleClass().add("dark");
        }
    }

    private void startBackupScheduler() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "bizflow-backup"); t.setDaemon(true); return t; });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                ctx.backup.runIfDue();
            } catch (RuntimeException e) {
                Platform.runLater(() -> Ui.toast("Backup failed", e.getMessage()));
            }
        }, 1, 30, TimeUnit.MINUTES);
    }

    @Override
    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }
}
