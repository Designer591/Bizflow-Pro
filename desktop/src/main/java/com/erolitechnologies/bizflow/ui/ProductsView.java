package com.erolitechnologies.bizflow.ui;

import com.erolitechnologies.bizflow.App;
import com.erolitechnologies.bizflow.AppContext;
import com.erolitechnologies.bizflow.infra.AppException;
import com.erolitechnologies.bizflow.licensing.Edition;
import com.erolitechnologies.bizflow.security.Permission;
import com.erolitechnologies.bizflow.service.BranchService;
import com.erolitechnologies.bizflow.service.ProductService;
import com.erolitechnologies.bizflow.service.Validate;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public final class ProductsView extends View {
    private final TableView<ProductService.Product> table = Ui.table("No products found");
    private final TextField text = Ui.field("Search name, SKU or barcode");
    private final ComboBox<ProductService.Category> category = Ui.combo(new ArrayList<ProductService.Category>(), ProductService.Category::name);
    private final CheckBox lowOnly = new CheckBox("Low stock only");
    private final CheckBox archived = new CheckBox("Show archived");
    private final Label valuation = new Label();

    public ProductsView(AppContext ctx, App app) { super(ctx, app); }

    @Override
    public Node build() {
        Ui.col(table, "SKU", ProductService.Product::sku, 90);
        Ui.col(table, "Name", ProductService.Product::name, 220);
        Ui.col(table, "Category", ProductService.Product::categoryName, 110);
        Ui.colRight(table, "Cost", p -> Ui.money(p.cost()), 80);
        Ui.colRight(table, "Price", p -> Ui.money(p.price()), 80);
        Ui.colRight(table, "In stock", p -> Ui.qty(p.stock()) + " " + p.unit(), 90);
        Ui.colRight(table, "Min", p -> Ui.qty(p.minStock()), 60);
        Ui.col(table, "Status", p -> p.archived() ? "Archived" : (p.lowStock() ? "LOW STOCK" : "OK"), 90);
        table.setRowFactory(tv -> new TableRow<>() {
            @Override protected void updateItem(ProductService.Product p, boolean empty) {
                super.updateItem(p, empty);
                getStyleClass().remove("row-warn");
                if (!empty && p != null && p.lowStock() && !p.archived()) getStyleClass().add("row-warn");
            }
        });
        reloadCategories();
        category.setPromptText("All categories");
        text.setOnAction(e -> load());
        category.setOnAction(e -> load());
        lowOnly.setOnAction(e -> load());
        archived.setOnAction(e -> load());
        Button all = Ui.button("Clear filters", () -> { text.clear(); category.setValue(null); lowOnly.setSelected(false); archived.setSelected(false); load(); });
        HBox filters = Ui.row(8, text, category, lowOnly, archived, all);
        HBox.setHgrow(text, Priority.ALWAYS);

        boolean manage = can(Permission.PRODUCT_MANAGE);
        Button add = Ui.primary("New product", () -> edit(null));
        Button edit = Ui.button("Edit", () -> edit(selected()));
        Button arch = Ui.button("Archive / restore", this::toggleArchive);
        Button cats = Ui.button("Categories", this::manageCategories);
        add.setDisable(!manage); edit.setDisable(!manage); arch.setDisable(!manage); cats.setDisable(!manage);
        boolean stockOk = can(Permission.STOCK_ADJUST);
        Button adjust = Ui.button("Adjust stock", this::adjust);
        adjust.setDisable(!stockOk);
        Button transfer = Ui.button("Transfer to branch", this::transfer);
        transfer.setDisable(!stockOk || !ctx.licence.hasFeature(Edition.Feature.MULTI_BRANCH));
        Button moves = Ui.button("Stock history", this::history);
        HBox tools = Ui.row(8, add, edit, arch, cats, adjust, transfer, moves, Ui.spacer(), valuation);
        VBox page = Ui.page("Inventory", filters, table, tools);
        load();
        return page;
    }

    private void reloadCategories() {
        category.getItems().setAll(ctx.products.categories());
    }

    private void load() {
        Ui.guard(() -> {
            var cat = category.getValue();
            table.getItems().setAll(ctx.products.search(s(), text.getText(), cat == null ? null : cat.id(), archived.isSelected(), lowOnly.isSelected()));
            var v = ctx.products.valuation(s(), s().branchId());
            valuation.setText("Stock value: cost " + Ui.money(v.costValue()) + "  |  retail " + Ui.money(v.retailValue()) + "  |  " + v.productCount() + " lines");
        });
    }

    private ProductService.Product selected() {
        var p = table.getSelectionModel().getSelectedItem();
        if (p == null) throw new AppException("Select a product first.");
        return p;
    }

    private void edit(ProductService.Product p) {
        boolean isNew = p == null;
        TextField sku = Ui.field(isNew ? "Leave empty to generate automatically" : "SKU");
        TextField barcode = Ui.field("Barcode (scan it here)");
        TextField name = Ui.field("Product name");
        TextField desc = Ui.field("Description (optional)");
        TextField unit = Ui.field("each, kg, litre, box...");
        TextField cost = Ui.field("0.00");
        TextField price = Ui.field("0.00");
        TextField min = Ui.field("0");
        TextField opening = Ui.field("0");
        TextField image = Ui.field("Optional image");
        CheckBox exempt = new CheckBox("Exempt from tax");
        ComboBox<ProductService.Category> cat = Ui.combo(ctx.products.categories(), ProductService.Category::name);
        if (!isNew) {
            sku.setText(p.sku()); barcode.setText(p.barcode() == null ? "" : p.barcode()); name.setText(p.name()); desc.setText(p.description());
            unit.setText(p.unit()); cost.setText(p.cost().toPlainString()); price.setText(p.price().toPlainString()); min.setText(Ui.qty(p.minStock()));
            image.setText(p.imagePath()); exempt.setSelected(p.taxExempt());
            for (var c : cat.getItems()) if (p.categoryId() != null && c.id() == p.categoryId()) cat.setValue(c);
        } else unit.setText("each");
        Button browse = Ui.button("Browse", () -> {
            File f = Ui.chooseOpen(app.stage(), "Product image", "Images", "*.png", "*.jpg", "*.jpeg");
            if (f != null) image.setText(f.getAbsolutePath());
        });
        GridPane g = Ui.form();
        int r = 0;
        Ui.addRow(g, r++, "Name *", name);
        Ui.addRow(g, r++, "SKU", sku);
        Ui.addRow(g, r++, "Barcode", barcode);
        Ui.addRow(g, r++, "Category", cat);
        Ui.addRow(g, r++, "Description", desc);
        Ui.addRow(g, r++, "Unit", unit);
        Ui.addRow(g, r++, "Purchase price", cost);
        Ui.addRow(g, r++, "Selling price *", price);
        Ui.addRow(g, r++, "Minimum stock", min);
        if (isNew) Ui.addRow(g, r++, "Opening stock", opening);
        Ui.addRow(g, r++, "Image", Ui.row(8, image, browse));
        HBox.setHgrow(image, Priority.ALWAYS);
        Ui.addRow(g, r, "", exempt);
        Ui.dialog(isNew ? "New product" : "Edit product", g, "Save", () -> {
            ProductService.ProductInput in = new ProductService.ProductInput(cat.getValue() == null ? null : cat.getValue().id(), null, sku.getText(), barcode.getText(),
                    name.getText(), desc.getText(), copyImage(image.getText()), unit.getText(), num(cost.getText(), "Purchase price"), num(price.getText(), "Selling price"),
                    exempt.isSelected(), num(min.getText(), "Minimum stock"), isNew ? num(opening.getText(), "Opening stock") : null);
            if (isNew) ctx.products.create(s(), in); else ctx.products.update(s(), p.id(), in);
            load();
        });
    }

    private static BigDecimal num(String t, String label) { return t == null || t.isBlank() ? BigDecimal.ZERO : Validate.decimal(t, label); }

    private String copyImage(String path) {
        if (path == null || path.isBlank()) return null;
        Path src = Path.of(path);
        if (src.startsWith(ctx.paths.assetsDir())) return path;
        try {
            Path dest = ctx.paths.assetsDir().resolve("p-" + System.currentTimeMillis() + "-" + src.getFileName());
            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
            return dest.toString();
        } catch (java.io.IOException | RuntimeException e) {
            throw new AppException("The image could not be copied. Choose a different file.", e);
        }
    }

    private void toggleArchive() {
        var p = selected();
        String what = p.archived() ? "Restore" : "Archive";
        if (Ui.confirm(what + " \"" + p.name() + "\"? " + (p.archived() ? "" : "It will no longer appear at the till, but sales history is kept."))) {
            ctx.products.setArchived(s(), p.id(), !p.archived());
            load();
        }
    }

    private void adjust() {
        var p = selected();
        TextField delta = Ui.field("+10 to add, -3 to remove");
        TextField reason = Ui.field("e.g. stock count correction, damaged goods");
        GridPane g = Ui.form();
        Ui.addRow(g, 0, "Current stock", new Label(Ui.qty(p.stock()) + " " + p.unit()));
        Ui.addRow(g, 1, "Change by", delta);
        Ui.addRow(g, 2, "Reason *", reason);
        Ui.dialog("Adjust stock - " + p.name(), g, "Apply", () -> {
            var bal = ctx.stock.adjust(s(), p.id(), Validate.decimal(delta.getText(), "Change"), reason.getText());
            Ui.info("New stock level: " + Ui.qty(bal));
            load();
        });
    }

    private void transfer() {
        var p = selected();
        List<BranchService.Branch> others = new ArrayList<>();
        for (var b : ctx.branches.list()) if (b.active() && b.id() != s().branchId()) others.add(b);
        if (others.isEmpty()) throw new AppException("Create another branch first (People & Access > Branches).");
        ComboBox<BranchService.Branch> to = Ui.combo(others, BranchService.Branch::name);
        to.setValue(others.get(0));
        TextField qty = Ui.field("Quantity");
        GridPane g = Ui.form();
        Ui.addRow(g, 0, "To branch", to);
        Ui.addRow(g, 1, "Quantity", qty);
        Ui.dialog("Transfer " + p.name(), g, "Transfer", () -> {
            ctx.stock.transfer(s(), s().branchId(), to.getValue().id(), p.id(), Validate.decimal(qty.getText(), "Quantity"));
            load();
        });
    }

    private void history() {
        var p = selected();
        TableView<com.erolitechnologies.bizflow.service.StockService.Movement> t = Ui.table("No movements");
        Ui.col(t, "When", m -> Ui.date(m.when()), 120);
        Ui.col(t, "Type", com.erolitechnologies.bizflow.service.StockService.Movement::type, 110);
        Ui.colRight(t, "Change", m -> Ui.qty(m.qty()), 80);
        Ui.colRight(t, "Balance", m -> Ui.qty(m.balance()), 80);
        Ui.col(t, "By", com.erolitechnologies.bizflow.service.StockService.Movement::user, 90);
        Ui.col(t, "Note", com.erolitechnologies.bizflow.service.StockService.Movement::note, 200);
        t.getItems().addAll(ctx.stock.movements(s(), p.id(), 500));
        t.setPrefSize(760, 420);
        Ui.dialog("Stock history - " + p.name(), t, "Close", () -> { });
    }

    private void manageCategories() {
        ListView<ProductService.Category> list = new ListView<>();
        list.getItems().addAll(ctx.products.categories());
        list.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(ProductService.Category c, boolean empty) { super.updateItem(c, empty); setText(empty || c == null ? null : c.name()); }
        });
        Button add = Ui.primary("Add", () -> Ui.prompt("New category", "Category name:", "").ifPresent(n -> Ui.guard(() -> {
            ctx.products.createCategory(s(), n);
            list.getItems().setAll(ctx.products.categories());
            reloadCategories();
        })));
        Button del = Ui.danger("Delete", () -> {
            var c = list.getSelectionModel().getSelectedItem();
            if (c == null) throw new AppException("Select a category first.");
            ctx.products.deleteCategory(s(), c.id());
            list.getItems().setAll(ctx.products.categories());
            reloadCategories();
        });
        list.setPrefSize(320, 320);
        Ui.dialog("Categories", new VBox(10, list, Ui.row(8, add, del)), "Close", () -> { });
    }
}
