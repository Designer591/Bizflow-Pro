package com.erolitechnologies.bizflow.ui;

import com.erolitechnologies.bizflow.App;
import com.erolitechnologies.bizflow.AppContext;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Shown when the licence does not allow use (expired beyond grace, invalid, device limit). Data is never touched. */
public final class LicenceGateView extends View {
    public LicenceGateView(AppContext ctx, App app) { super(ctx, app); }

    @Override
    public Node build() {
        VBox card = new VBox(16);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(28));
        card.setMaxWidth(640);
        card.getChildren().addAll(Ui.h1("Licence required"),
                Ui.muted(ctx.licence.status().message() + " Your data is safe and will be available again as soon as the licence is renewed."),
                new LicencePanel(ctx, app, () -> { if (ctx.licence.status().allowsUse()) app.showMain(); else app.showLicenceGate(); }).build(),
                Ui.button("Sign out", () -> { ctx.auth.logout(ctx.session); app.showEntry(); }));
        StackPane p = new StackPane(card);
        p.setAlignment(Pos.CENTER);
        p.getStyleClass().add("login-right");
        return p;
    }
}
