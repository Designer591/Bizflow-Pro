package com.erolitechnologies.bizflow.ui;

import com.erolitechnologies.bizflow.App;
import com.erolitechnologies.bizflow.AppContext;
import com.erolitechnologies.bizflow.infra.AppException;
import com.erolitechnologies.bizflow.licensing.Edition;
import com.erolitechnologies.bizflow.security.Permission;
import com.erolitechnologies.bizflow.security.Role;
import com.erolitechnologies.bizflow.service.BranchService;
import com.erolitechnologies.bizflow.service.EmployeeService;
import com.erolitechnologies.bizflow.service.UserService;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** User accounts, role permissions, employee profiles and (Enterprise) branches. */
public final class PeopleView extends View {
    private final TableView<UserService.UserInfo> users = Ui.table("No users");
    private final TableView<EmployeeService.Employee> employees = Ui.table("No employees");

    public PeopleView(AppContext ctx, App app) { super(ctx, app); }

    @Override
    public Node build() {
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().add(new Tab("User accounts", usersTab()));
        tabs.getTabs().add(new Tab("Role permissions", rolesTab()));
        if (can(Permission.EMPLOYEE_MANAGE)) tabs.getTabs().add(new Tab("Employees", employeesTab()));
        if (can(Permission.BRANCH_MANAGE) && ctx.licence.hasFeature(Edition.Feature.MULTI_BRANCH)) tabs.getTabs().add(new Tab("Branches", branchesTab()));
        VBox.setVgrow(tabs, Priority.ALWAYS);
        return Ui.page("People & Access", tabs);
    }

    private static String roleLabel(String name) {
        try { return Role.valueOf(name).label(); } catch (RuntimeException e) { return name; }
    }

    // ---------------- users ----------------
    private Node usersTab() {
        Ui.col(users, "Username", UserService.UserInfo::username, 120);
        Ui.col(users, "Name", UserService.UserInfo::fullName, 170);
        Ui.col(users, "Role", u -> roleLabel(u.role()), 130);
        Ui.col(users, "Status", u -> u.active() ? "Active" : "Deactivated", 90);
        Ui.col(users, "Last sign-in", u -> Ui.date(u.lastLogin()), 130);
        Button add = Ui.primary("New user", () -> editUser(null));
        Button edit = Ui.button("Edit", () -> editUser(selUser()));
        Button toggle = Ui.button("Activate / deactivate", () -> { var u = selUser(); ctx.users.setActive(s(), u.id(), !u.active()); loadUsers(); });
        Button reset = Ui.button("Set new password", this::resetPw);
        Button code = Ui.button("Issue recovery code", () -> {
            var u = selUser();
            String c = ctx.auth.createRecoveryCode(s(), u.id());
            Ui.info("Give this one-time code to " + u.fullName() + ". It works for 30 minutes:\n\n" + c);
        });
        VBox box = new VBox(10, users, Ui.row(8, add, edit, toggle, reset, code));
        box.setPadding(new Insets(12));
        loadUsers();
        return box;
    }

    private void loadUsers() { Ui.guard(() -> users.getItems().setAll(ctx.users.list(s()))); }

    private UserService.UserInfo selUser() {
        var u = users.getSelectionModel().getSelectedItem();
        if (u == null) throw new AppException("Select a user first.");
        return u;
    }

    private void editUser(UserService.UserInfo u) {
        TextField username = Ui.field("Username"), name = Ui.field("Full name"), email = Ui.field("Email"), phone = Ui.field("Phone");
        PasswordField pw = new PasswordField();
        ComboBox<Role> role = Ui.combo(Arrays.asList(Role.values()), Role::label);
        role.setValue(Role.CASHIER);
        if (u != null) {
            username.setText(u.username()); username.setDisable(true); name.setText(u.fullName()); email.setText(u.email()); phone.setText(u.phone());
            try { role.setValue(Role.valueOf(u.role())); } catch (RuntimeException ignored) { }
        }
        GridPane g = Ui.form();
        int r = 0;
        Ui.addRow(g, r++, "Username *", username);
        Ui.addRow(g, r++, "Full name *", name);
        Ui.addRow(g, r++, "Role *", role);
        Ui.addRow(g, r++, "Email", email);
        Ui.addRow(g, r++, "Phone", phone);
        if (u == null) Ui.addRow(g, r, "Temporary password *", pw);
        Ui.dialog(u == null ? "New user" : "Edit user", g, "Save", () -> {
            if (u == null) ctx.users.create(s(), username.getText(), name.getText(), email.getText(), phone.getText(), pw.getText(), role.getValue(), null);
            else ctx.users.update(s(), u.id(), name.getText(), email.getText(), phone.getText(), role.getValue(), null);
            loadUsers();
        });
    }

    private void resetPw() {
        var u = selUser();
        PasswordField pw = new PasswordField();
        GridPane g = Ui.form();
        Ui.addRow(g, 0, "New password", pw);
        Ui.dialog("Set password for " + u.username(), g, "Set password", () -> { ctx.users.adminResetPassword(s(), u.id(), pw.getText()); Ui.info("Password changed."); });
    }

    // ---------------- role permissions ----------------
    private Node rolesTab() {
        ComboBox<Role> role = Ui.combo(Arrays.asList(Role.values()), Role::label);
        role.setValue(Role.CASHIER);
        VBox checks = new VBox(6);
        Map<Permission, CheckBox> boxes = new LinkedHashMap<>();
        Runnable load = () -> {
            checks.getChildren().clear();
            boxes.clear();
            Set<Permission> have = ctx.users.permissionsOf(role.getValue());
            for (Permission p : Permission.values()) {
                CheckBox cb = new CheckBox(p.description());
                cb.setSelected(have.contains(p));
                cb.setDisable(role.getValue() == Role.ADMINISTRATOR);
                boxes.put(p, cb);
                checks.getChildren().add(cb);
            }
        };
        role.setOnAction(e -> Ui.guard(load));
        Button save = Ui.primary("Save permissions", () -> {
            Set<Permission> chosen = EnumSet.noneOf(Permission.class);
            for (var e : boxes.entrySet()) if (e.getValue().isSelected()) chosen.add(e.getKey());
            ctx.users.setRolePermissions(s(), role.getValue(), chosen);
            Ui.info("Permissions saved. They apply the next time each person signs in.");
        });
        ScrollPane sp = new ScrollPane(checks);
        sp.setFitToWidth(true);
        VBox.setVgrow(sp, Priority.ALWAYS);
        VBox box = new VBox(10, Ui.muted("Choose a role and tick what people in that role may do. Administrators always have every permission."), Ui.row(8, new Label("Role"), role, save), sp);
        box.setPadding(new Insets(12));
        Ui.guard(load);
        return box;
    }

    // ---------------- employees ----------------
    private Node employeesTab() {
        Ui.col(employees, "Name", EmployeeService.Employee::fullName, 160);
        Ui.col(employees, "Username", e -> e.username() == null ? "" : e.username(), 110);
        Ui.col(employees, "Role", e -> roleLabel(e.role() == null ? "" : e.role()), 120);
        Ui.col(employees, "Job title", e -> e.jobTitle() == null ? "" : e.jobTitle(), 130);
        Ui.col(employees, "Hired", e -> e.hiredOn() == null ? "" : e.hiredOn(), 90);
        Ui.col(employees, "Status", e -> e.active() ? "Active" : "Inactive", 80);
        Button edit = Ui.button("Edit profile", this::editEmployee);
        Button act = Ui.button("Activity history", this::activity);
        VBox box = new VBox(10, employees, Ui.row(8, edit, act));
        box.setPadding(new Insets(12));
        Ui.guard(() -> employees.getItems().setAll(ctx.employees.list(s())));
        return box;
    }

    private EmployeeService.Employee selEmp() {
        var e = employees.getSelectionModel().getSelectedItem();
        if (e == null) throw new AppException("Select an employee first.");
        return e;
    }

    private void editEmployee() {
        var e = selEmp();
        TextField title = Ui.field("Job title"), nid = Ui.field("National ID"), hired = Ui.field("Date hired, YYYY-MM-DD");
        title.setText(e.jobTitle() == null ? "" : e.jobTitle());
        nid.setText(e.nationalId() == null ? "" : e.nationalId());
        hired.setText(e.hiredOn() == null ? "" : e.hiredOn());
        GridPane g = Ui.form();
        Ui.addRow(g, 0, "Job title", title); Ui.addRow(g, 1, "National ID", nid); Ui.addRow(g, 2, "Date hired", hired);
        Ui.dialog("Employee - " + e.fullName(), g, "Save", () -> {
            ctx.employees.updateProfile(s(), e.id(), title.getText(), nid.getText(), hired.getText());
            employees.getItems().setAll(ctx.employees.list(s()));
        });
    }

    private void activity() {
        var e = selEmp();
        if (e.userId() == null) throw new AppException("This employee has no user account, so there is no activity to show.");
        TableView<EmployeeService.Activity> t = Ui.table("No activity recorded");
        Ui.col(t, "When", a -> Ui.date(a.when()), 120);
        Ui.col(t, "Action", EmployeeService.Activity::action, 160);
        Ui.col(t, "Item", EmployeeService.Activity::entity, 90);
        Ui.col(t, "Details", EmployeeService.Activity::details, 300);
        t.getItems().addAll(ctx.employees.activity(s(), e.userId(), 300));
        t.setPrefSize(760, 400);
        Ui.dialog("Activity - " + e.fullName(), t, "Close", () -> { });
    }

    // ---------------- branches ----------------
    private Node branchesTab() {
        TableView<BranchService.Branch> t = Ui.table("No branches");
        Ui.col(t, "Code", BranchService.Branch::code, 80);
        Ui.col(t, "Name", BranchService.Branch::name, 180);
        Ui.col(t, "Address", BranchService.Branch::address, 220);
        Ui.col(t, "Phone", BranchService.Branch::phone, 110);
        Ui.col(t, "Status", b -> (b.main() ? "Main, " : "") + (b.active() ? "Active" : "Inactive"), 100);
        Runnable load = () -> t.getItems().setAll(ctx.branches.list());
        Button add = Ui.primary("New branch", () -> {
            TextField code = Ui.field("e.g. BYO"), name = Ui.field("Branch name"), addr = Ui.field("Address"), phone = Ui.field("Phone");
            GridPane g = Ui.form();
            Ui.addRow(g, 0, "Code *", code); Ui.addRow(g, 1, "Name *", name); Ui.addRow(g, 2, "Address", addr); Ui.addRow(g, 3, "Phone", phone);
            Ui.dialog("New branch", g, "Create", () -> { ctx.branches.create(s(), name.getText(), code.getText(), addr.getText(), phone.getText()); load.run(); });
        });
        Button toggle = Ui.button("Activate / deactivate", () -> {
            var b = t.getSelectionModel().getSelectedItem();
            if (b == null) throw new AppException("Select a branch first.");
            ctx.branches.update(s(), b.id(), b.name(), b.address(), b.phone(), !b.active());
            load.run();
        });
        VBox box = new VBox(10, Ui.muted("Branch data (stock, sales, staff) is stored per branch. Multi-branch synchronisation across computers needs the central server described in the documentation."),
                t, Ui.row(8, add, toggle));
        box.setPadding(new Insets(12));
        load.run();
        return box;
    }
}
