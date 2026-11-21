package com.erolitechnologies.bizflow.ui;

import com.erolitechnologies.bizflow.App;
import com.erolitechnologies.bizflow.AppContext;
import com.erolitechnologies.bizflow.security.Session;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

public final class LoginView extends View {
    public LoginView(AppContext ctx, App app) { super(ctx, app); }

    @Override
    public Node build() {
        String biz = ctx.business.get() == null ? "BizFlow Pro" : ctx.business.get().name();
        Label brand = new Label("BizFlow Pro");
        brand.getStyleClass().add("login-title");
        Label tag = new Label("Business Management Made Simple.");
        tag.getStyleClass().add("login-tag");
        Label by = new Label("Powered by EROLITECHNOLOGIES");
        by.getStyleClass().add("login-by");
        VBox left = new VBox(10, brand, tag, Ui.spacer(), by);
        left.getStyleClass().add("login-left");
        left.setPadding(new Insets(48));
        left.setPrefWidth(460);

        Label welcome = Ui.h1("Welcome back");
        Label sub = Ui.muted("Sign in to " + biz);
        TextField user = Ui.field("Username");
        PasswordField pass = new PasswordField();
        pass.setPromptText("Password");
        Label error = new Label();
        error.getStyleClass().add("error-text");
        error.setWrapText(true);
        Runnable doLogin = () -> {
            error.setText("");
            try {
                Session s = ctx.auth.login(user.getText(), pass.getText());
                pass.clear();
                ctx.session = s;
                Ui.setCurrency(ctx.settings.get("currency_symbol"));
                if (!ctx.licence.status().allowsUse()) app.showLicenceGate(); else app.showMain();
            } catch (RuntimeException ex) {
                error.setText(ex instanceof com.erolitechnologies.bizflow.infra.AppException ? ex.getMessage() : "Sign-in failed. Please try again.");
                if (!(ex instanceof com.erolitechnologies.bizflow.infra.AppException)) com.erolitechnologies.bizflow.infra.Log.error("Login error", ex);
                pass.clear();
            }
        };
        Button login = Ui.primary("Sign in", doLogin);
        login.setMaxWidth(Double.MAX_VALUE);
        login.setDefaultButton(true);
        pass.setOnAction(e -> doLogin.run());
        Hyperlink forgot = new Hyperlink("Forgot password?");
        forgot.setOnAction(e -> forgotPassword(user.getText()));
        VBox form = new VBox(12, welcome, sub, new Label("Username"), user, new Label("Password"), pass, error, login, forgot);
        form.setMaxWidth(360);
        form.getStyleClass().add("card");
        form.setPadding(new Insets(28));
        StackPane right = new StackPane(form);
        right.setAlignment(Pos.CENTER);
        right.getStyleClass().add("login-right");
        HBox.setHgrow(right, Priority.ALWAYS);
        return new HBox(left, right);
    }

    private void forgotPassword(String prefillUser) {
        TextField user = Ui.field("Username");
        user.setText(prefillUser == null ? "" : prefillUser);
        TextField code = Ui.field("Recovery code, e.g. ABCD-2345");
        PasswordField pw1 = new PasswordField();
        PasswordField pw2 = new PasswordField();
        GridPane g = Ui.form();
        Ui.addRow(g, 0, "Username", user);
        Ui.addRow(g, 1, "Recovery code", code);
        Ui.addRow(g, 2, "New password", pw1);
        Ui.addRow(g, 3, "Repeat password", pw2);
        Label help = Ui.muted("Ask your administrator for a one-time recovery code (Users screen). If you are the administrator and locked out, "
                + "run  BizFlowPro.exe --recovery-code <username>  on this computer to get one.");
        VBox box = new VBox(10, help, g);
        box.setPrefWidth(460);
        Ui.dialog("Reset password", box, "Reset password", () -> {
            if (!pw1.getText().equals(pw2.getText())) throw new com.erolitechnologies.bizflow.infra.AppException("The two passwords do not match.");
            ctx.auth.resetPasswordWithCode(user.getText(), code.getText(), pw1.getText());
            Ui.info("Your password was changed. You can sign in now.");
        });
    }
}
