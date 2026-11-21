package com.erolitechnologies.bizflow.service;

import com.erolitechnologies.bizflow.domain.Money;
import com.erolitechnologies.bizflow.infra.AppException;
import com.erolitechnologies.bizflow.infra.Db;
import com.erolitechnologies.bizflow.security.Permission;
import com.erolitechnologies.bizflow.security.Session;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class ProductService {
    public record Product(long id, Long categoryId, String categoryName, Long supplierId, String sku, String barcode, String name,
                          String description, String imagePath, String unit, BigDecimal cost, BigDecimal price, boolean taxExempt,
                          BigDecimal minStock, boolean archived, BigDecimal stock) {
        public boolean lowStock() { return minStock.signum() > 0 && stock.compareTo(minStock) <= 0; }
    }

    public record ProductInput(Long categoryId, Long supplierId, String sku, String barcode, String name, String description,
                               String imagePath, String unit, BigDecimal cost, BigDecimal price, boolean taxExempt,
                               BigDecimal minStock, BigDecimal openingStock) {
        public ProductInput {
            if (cost == null) cost = BigDecimal.ZERO;
            if (price == null) price = BigDecimal.ZERO;
            if (minStock == null) minStock = BigDecimal.ZERO;
        }
    }

    public record Category(long id, String name) {}

    public record Valuation(BigDecimal costValue, BigDecimal retailValue, int productCount, int units) {}

    private static final String SELECT =
            "SELECT p.id, p.category_id, COALESCE(c.name,''), p.supplier_id, p.sku, p.barcode, p.name, COALESCE(p.description,''), COALESCE(p.image_path,''), " +
            "p.unit, p.cost_minor, p.price_minor, p.tax_exempt, p.min_stock_milli, p.archived, COALESCE(bs.qty_milli,0) " +
            "FROM products p LEFT JOIN categories c ON c.id = p.category_id LEFT JOIN branch_stock bs ON bs.product_id = p.id AND bs.branch_id = ? ";

    private final Db db;

    public ProductService(Db db) { this.db = db; }

    private static Product map(java.sql.ResultSet rs) throws SQLException {
        return new Product(rs.getLong(1), (Long) rs.getObject(2), rs.getString(3), (Long) rs.getObject(4), rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getString(8), rs.getString(9), rs.getString(10), Money.fromMinor(rs.getLong(11)), Money.fromMinor(rs.getLong(12)), rs.getInt(13) == 1,
                Money.qtyFromMilli(rs.getLong(14)), rs.getInt(15) == 1, Money.qtyFromMilli(rs.getLong(16)));
    }

    public List<Product> search(Session s, String text, Long categoryId, boolean includeArchived, boolean lowOnly) {
        s.require(Permission.PRODUCT_VIEW);
        return db.read(c -> {
            StringBuilder sql = new StringBuilder(SELECT).append("WHERE 1=1 ");
            List<Object> args = new ArrayList<>();
            args.add(s.branchId());
            if (text != null && !text.isBlank()) {
                sql.append("AND (p.name LIKE ? OR p.sku LIKE ? OR p.barcode LIKE ?) ");
                String like = "%" + text.trim() + "%";
                args.add(like); args.add(like); args.add(like);
            }
            if (categoryId != null) { sql.append("AND p.category_id = ? "); args.add(categoryId); }
            if (!includeArchived) sql.append("AND p.archived = 0 ");
            if (lowOnly) sql.append("AND p.min_stock_milli > 0 AND COALESCE(bs.qty_milli,0) <= p.min_stock_milli ");
            sql.append("ORDER BY p.name LIMIT 2000");
            return Db.query(c, sql.toString(), ProductService::map, args.toArray());
        });
    }

    /** Exact SKU/barcode match, used by barcode scanners (which type the code then press Enter). */
    public Product findByCode(Session s, String code) {
        s.require(Permission.PRODUCT_VIEW);
        if (code == null || code.isBlank()) return null;
        return db.read(c -> Db.queryOne(c, SELECT + "WHERE p.archived = 0 AND (p.barcode = ? OR p.sku = ? COLLATE NOCASE)", ProductService::map, s.branchId(), code.trim(), code.trim()));
    }

    public Product get(Session s, long id) {
        s.require(Permission.PRODUCT_VIEW);
        return db.read(c -> Db.queryOne(c, SELECT + "WHERE p.id = ?", ProductService::map, s.branchId(), id));
    }

    public long create(Session s, ProductInput in) {
        s.require(Permission.PRODUCT_MANAGE);
        validate(in);
        return db.tx(c -> {
            String sku = Validate.optional(in.sku());
            long id;
            try {
                if (sku == null) {
                    id = Db.insert(c, "INSERT INTO products(category_id, supplier_id, sku, barcode, name, description, image_path, unit, cost_minor, price_minor, tax_exempt, min_stock_milli) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                            in.categoryId(), in.supplierId(), "TMP-" + System.nanoTime(), Validate.optional(in.barcode()), in.name().trim(), Validate.optional(in.description()),
                            Validate.optional(in.imagePath()), unit(in), Money.toMinor(in.cost()), Money.toMinor(in.price()), in.taxExempt(), Money.qtyToMilli(in.minStock()));
                    Db.update(c, "UPDATE products SET sku = ? WHERE id = ?", String.format("PRD-%06d", id), id);
                } else {
                    id = Db.insert(c, "INSERT INTO products(category_id, supplier_id, sku, barcode, name, description, image_path, unit, cost_minor, price_minor, tax_exempt, min_stock_milli) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                            in.categoryId(), in.supplierId(), sku, Validate.optional(in.barcode()), in.name().trim(), Validate.optional(in.description()),
                            Validate.optional(in.imagePath()), unit(in), Money.toMinor(in.cost()), Money.toMinor(in.price()), in.taxExempt(), Money.qtyToMilli(in.minStock()));
                }
            } catch (SQLException e) {
                if (String.valueOf(e.getMessage()).toLowerCase().contains("unique")) throw new AppException("A product with that SKU or barcode already exists.", e);
                throw e;
            }
            if (in.openingStock() != null && in.openingStock().signum() > 0)
                StockService.apply(c, s.branchId(), id, in.openingStock(), "OPENING", "product", id, "Opening stock", s.userId(), false);
            AuditService.log(c, s, "PRODUCT_CREATED", "product", id, in.name().trim());
            return id;
        });
    }

    public void update(Session s, long id, ProductInput in) {
        s.require(Permission.PRODUCT_MANAGE);
        validate(in);
        Validate.required(in.sku(), "SKU");
        db.txVoid(c -> {
            try {
                int n = Db.update(c, "UPDATE products SET category_id=?, supplier_id=?, sku=?, barcode=?, name=?, description=?, image_path=?, unit=?, cost_minor=?, price_minor=?, tax_exempt=?, min_stock_milli=? WHERE id=?",
                        in.categoryId(), in.supplierId(), in.sku().trim(), Validate.optional(in.barcode()), in.name().trim(), Validate.optional(in.description()),
                        Validate.optional(in.imagePath()), unit(in), Money.toMinor(in.cost()), Money.toMinor(in.price()), in.taxExempt(), Money.qtyToMilli(in.minStock()), id);
                if (n == 0) throw new AppException("Product not found.");
            } catch (SQLException e) {
                if (String.valueOf(e.getMessage()).toLowerCase().contains("unique")) throw new AppException("A product with that SKU or barcode already exists.", e);
                throw e;
            }
            AuditService.log(c, s, "PRODUCT_UPDATED", "product", id, in.name().trim());
            return null;
        });
    }

    /** Products are archived, never deleted, so sales history stays intact. */
    public void setArchived(Session s, long id, boolean archived) {
        s.require(Permission.PRODUCT_MANAGE);
        db.txVoid(c -> {
            Db.update(c, "UPDATE products SET archived = ? WHERE id = ?", archived, id);
            AuditService.log(c, s, archived ? "PRODUCT_ARCHIVED" : "PRODUCT_RESTORED", "product", id, null);
            return null;
        });
    }

    public Valuation valuation(Session s, long branchId) {
        s.require(Permission.PRODUCT_VIEW);
        return db.read(c -> {
            long[] v = Db.queryOne(c,
                    "SELECT COALESCE(SUM(bs.qty_milli * p.cost_minor / 1000),0), COALESCE(SUM(bs.qty_milli * p.price_minor / 1000),0), COUNT(*), COALESCE(SUM(bs.qty_milli / 1000),0) " +
                    "FROM branch_stock bs JOIN products p ON p.id = bs.product_id WHERE p.archived = 0 AND bs.qty_milli > 0 AND (? = 0 OR bs.branch_id = ?)",
                    rs -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)}, branchId, branchId);
            return new Valuation(Money.fromMinor(v[0]), Money.fromMinor(v[1]), (int) v[2], (int) v[3]);
        });
    }

    public List<Category> categories() {
        return db.read(c -> Db.query(c, "SELECT id, name FROM categories ORDER BY name", rs -> new Category(rs.getLong(1), rs.getString(2))));
    }

    public long createCategory(Session s, String name) {
        s.require(Permission.PRODUCT_MANAGE);
        Validate.required(name, "Category name");
        return db.tx(c -> {
            long id = Db.insert(c, "INSERT INTO categories(name) VALUES (?)", name.trim());
            AuditService.log(c, s, "CATEGORY_CREATED", "category", id, name.trim());
            return id;
        });
    }

    public void deleteCategory(Session s, long id) {
        s.require(Permission.PRODUCT_MANAGE);
        db.txVoid(c -> {
            if (Db.scalarLong(c, "SELECT COUNT(*) FROM products WHERE category_id = ?", id) > 0)
                throw new AppException("This category still has products. Move them to another category first.");
            Db.update(c, "DELETE FROM categories WHERE id = ?", id);
            AuditService.log(c, s, "CATEGORY_DELETED", "category", id, null);
            return null;
        });
    }

    private static String unit(ProductInput in) { return in.unit() == null || in.unit().isBlank() ? "each" : in.unit().trim(); }

    private static void validate(ProductInput in) {
        Validate.required(in.name(), "Product name");
        Validate.maxLen(in.name(), 150, "Product name");
        Validate.nonNegative(in.cost(), "Purchase price");
        Validate.nonNegative(in.price(), "Selling price");
        Validate.nonNegative(in.minStock() == null ? BigDecimal.ZERO : in.minStock(), "Minimum stock");
        if (in.sku() != null && !in.sku().isBlank() && in.sku().length() > 40) throw new AppException("SKU is too long (maximum 40 characters).");
    }
}
