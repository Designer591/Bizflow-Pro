package com.erolitechnologies.bizflow.ui;

import com.erolitechnologies.bizflow.App;
import com.erolitechnologies.bizflow.AppContext;
import com.erolitechnologies.bizflow.security.Permission;
import com.erolitechnologies.bizflow.service.LicenceService;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;

/** Licence status plus offline (paste file contents) and online (licence key) activation. Used in Settings and on the licence gate. */
public final class LicencePanel extends View {
    private final Runnable onChanged;

    public LicencePanel(AppContext ctx, App app, Runnable onChanged) {
        super(ctx, app);
        this.onChanged = onChanged;
    }

    @Override
    public Node build() {
        LicenceService.Status st = ctx.licence.status();
        GridPane info = Ui.form();
        Ui.addRow(info, 0, "Status", new Label(st.message()));
        Ui.addRow(info, 1, "Edition", new Label(st.edition() == null ? "-" : st.edition().name()));
        Ui.addRow(info, 2, "Licensed to", new Label(st.licensee() == null ? "-" : st.licensee()));
        Ui.addRow(info, 3, "Type", new Label(st.kind() == null ? (st.evaluation() ? "Evaluation" : "-") : st.kind().name()));
        Ui.addRow(info, 4, "Expires", new Label(st.expiresAt() == null ? (st.kind() == null ? "-" : "Never") : st.expiresAt().toString().substring(0, 10) + " (" + st.daysLeft() + " days)"));
        TextField dev = new TextField(ctx.licence.deviceId());
        dev.setEditable(false);
        Button copy = Ui.button("Copy", () -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(ctx.licence.deviceId());
            Clipboard.getSystemClipboard().setContent(cc);
        });
        Ui.addRow(info, 5, "This device ID", Ui.row(8, dev, copy));

        VBox box = new VBox(14, Ui.h2("Licence"), info);
        if (!can(Permission.LICENCE_MANAGE)) {
            box.getChildren().add(Ui.muted("Only an administrator can change the licence."));
            return box;
        }
        TextArea token = new TextArea();
        token.setPromptText("Paste the licence text you received from EROLITECHNOLOGIES here");
        token.setPrefRowCount(4);
        token.setWrapText(true);
        Button install = Ui.primary("Install licence", () -> {
            ctx.licence.install(s(), token.getText());
            Ui.info("Licence installed.");
            onChanged.run();
        });
        TextField key = Ui.field("Licence key");
        TextField url = Ui.field("https://licences.example.com");
        url.setText(ctx.settings.get("licence_server_url"));
        Button online = Ui.primary("Activate online", () -> {
            ctx.licence.activateOnline(s(), key.getText(), url.getText());
            Ui.info("This device is now activated.");
            onChanged.run();
        });
        Button refresh = Ui.button("Check for renewal", () -> {
            ctx.licence.refreshOnline(s(), key.getText());
            Ui.info("Licence refreshed.");
            onChanged.run();
        });
        box.getChildren().addAll(Ui.h2("Offline activation"), Ui.muted("Send the device ID above to EROLITECHNOLOGIES and paste the licence you receive."), token, install,
                new Separator(), Ui.h2("Online activation"), Ui.muted("Requires internet access and the licence server address supplied with your purchase."),
                key, url, Ui.row(8, online, refresh));
        return box;
    }
}
