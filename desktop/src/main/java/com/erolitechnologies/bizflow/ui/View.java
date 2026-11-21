package com.erolitechnologies.bizflow.ui;

import com.erolitechnologies.bizflow.App;
import com.erolitechnologies.bizflow.AppContext;
import com.erolitechnologies.bizflow.security.Permission;
import com.erolitechnologies.bizflow.security.Session;
import javafx.scene.Node;

/** Base for every screen. Screens talk to services only; they never touch SQL. */
public abstract class View {
    protected final AppContext ctx;
    protected final App app;

    protected View(AppContext ctx, App app) {
        this.ctx = ctx;
        this.app = app;
    }

    protected Session s() { return ctx.session; }

    protected boolean can(Permission p) { return ctx.session != null && ctx.session.has(p); }

    public abstract Node build();
}
