# BizFlow Pro - Database documentation

Generated from `desktop/src/main/resources/db/migration/V1__initial_schema.sql`.

## Conventions

- Engine: SQLite (WAL mode, `foreign_keys=ON`). Migrations are listed in `db/migration/index.txt`, applied once in order by `Migrator`, and recorded in `schema_info`.
- **Money is stored as integer minor units** (`*_minor`, e.g. cents) and converted with `BigDecimal` at scale 2 (HALF_UP) in `domain/Money`.
- **Quantities are stored as integer thousandths** (`*_milli`) so kilograms/metres work without floating point.
- Timestamps are local-time text (`YYYY-MM-DD HH:MM:SS`).
- Completed sales are never deleted. Reversals are new rows (`refunds`, `payments` with `direction='OUT'`, `stock_movements`), and a voided sale keeps `status='VOIDED'`.
- Stock is changed **only** through `StockService.apply`, one atomic conditional `UPDATE` per change plus a `stock_movements` row.

## Adding a migration

Create `V2__description.sql`, add its file name to `index.txt`. Never edit an already-released migration.

## Tables

### `businesses`

| Column | Definition |
|---|---|
| `id` | INTEGER PRIMARY KEY AUTOINCREMENT |
| `name` | TEXT NOT NULL |
| `business_type` | TEXT NOT NULL |
| `logo_path` | TEXT |
| `address` | TEXT |
| `phone` | TEXT |
| `email` | TEXT |
| `country` | TEXT NOT NULL |
| `currency_code` | TEXT NOT NULL DEFAULT 'USD' |
| `created_at` | TEXT NOT NULL DEFAULT (datetime('now','localtime')) |

### `branches`

| Column | Definition |
|---|---|
| `id` | INTEGER PRIMARY KEY AUTOINCREMENT |
| `business_id` | INTEGER NOT NULL REFERENCES businesses(id) |
| `name` | TEXT NOT NULL |
| `code` | TEXT NOT NULL |
| `address` | TEXT |
| `phone` | TEXT |
| `is_main` | INTEGER NOT NULL DEFAULT 0 |
| `active` | INTEGER NOT NULL DEFAULT 1 |
| *constraint* | `UNIQUE (business_id, code)` |

### `roles`

| Column | Definition |
|---|---|
| `id` | INTEGER PRIMARY KEY AUTOINCREMENT |
| `name` | TEXT NOT NULL UNIQUE |

### `permissions`

| Column | Definition |
|---|---|
| `id` | INTEGER PRIMARY KEY AUTOINCREMENT |
| `code` | TEXT NOT NULL UNIQUE |
| `description` | TEXT NOT NULL |

### `role_permissions`

| Column | Definition |
|---|---|
| `role_id` | INTEGER NOT NULL REFERENCES roles(id) ON DELETE CASCADE |
| `permission_id` | INTEGER NOT NULL REFERENCES permissions(id) ON DELETE CASCADE |
| *constraint* | `PRIMARY KEY (role_id, permission_id)` |

### `users`

| Column | Definition |
|---|---|
| `id` | INTEGER PRIMARY KEY AUTOINCREMENT |
| `branch_id` | INTEGER REFERENCES branches(id) |
| `username` | TEXT NOT NULL UNIQUE |
| `password_hash` | TEXT NOT NULL |
| `full_name` | TEXT NOT NULL |
| `email` | TEXT |
| `phone` | TEXT |
| `active` | INTEGER NOT NULL DEFAULT 1 |
| `failed_logins` | INTEGER NOT NULL DEFAULT 0 |
| `locked_until` | TEXT |
| `must_change_pw` | INTEGER NOT NULL DEFAULT 0 |
| `recovery_hash` | TEXT |
| `recovery_expires` | TEXT |
| `last_login_at` | TEXT |
| `created_at` | TEXT NOT NULL DEFAULT (datetime('now','localtime')) |

### `user_roles`

| Column | Definition |
|---|---|
| `user_id` | INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE |
| `role_id` | INTEGER NOT NULL REFERENCES roles(id) |
| *constraint* | `PRIMARY KEY (user_id, role_id)` |

### `employees`

| Column | Definition |
|---|---|
| `id` | INTEGER PRIMARY KEY AUTOINCREMENT |
| `user_id` | INTEGER UNIQUE REFERENCES users(id) |
| `branch_id` | INTEGER REFERENCES branches(id) |
| `full_name` | TEXT NOT NULL |
| `national_id` | TEXT |
| `phone` | TEXT |
| `email` | TEXT |
| `job_title` | TEXT |
| `hired_on` | TEXT |
| `active` | INTEGER NOT NULL DEFAULT 1 |

### `categories`

| Column | Definition |
|---|---|
| `id` | INTEGER PRIMARY KEY AUTOINCREMENT |
| `name` | TEXT NOT NULL UNIQUE |
| `parent_id` | INTEGER REFERENCES categories(id) |

### `suppliers`

| Column | Definition |
|---|---|
| `id` | INTEGER PRIMARY KEY AUTOINCREMENT |
| `name` | TEXT NOT NULL |
| `contact_name` | TEXT |
| `phone` | TEXT |
| `email` | TEXT |
| `address` | TEXT |
| `active` | INTEGER NOT NULL DEFAULT 1 |
| `created_at` | TEXT NOT NULL DEFAULT (datetime('now','localtime')) |

### `customers`

| Column | Definition |
|---|---|
| `id` | INTEGER PRIMARY KEY AUTOINCREMENT |
| `name` | TEXT NOT NULL |
| `phone` | TEXT |
| `email` | TEXT |
| `address` | TEXT |
| `credit_enabled` | INTEGER NOT NULL DEFAULT 0 |
| `credit_limit_minor` | INTEGER NOT NULL DEFAULT 0 CHECK (credit_limit_minor >= 0) |
| `balance_minor` | INTEGER NOT NULL DEFAULT 0 |
| `active` | INTEGER NOT NULL DEFAULT 1 |
| `created_at` | TEXT NOT NULL DEFAULT (datetime('now','localtime')) |

### `products`

| Column | Definition |
|---|---|
| `id` | INTEGER PRIMARY KEY AUTOINCREMENT |
| `category_id` | INTEGER REFERENCES categories(id) |
| `supplier_id` | INTEGER REFERENCES suppliers(id) |
| `sku` | TEXT NOT NULL UNIQUE |
| `barcode` | TEXT UNIQUE |
| `name` | TEXT NOT NULL |
| `description` | TEXT |
| `image_path` | TEXT |
| `unit` | TEXT NOT NULL DEFAULT 'each' |
| `cost_minor` | INTEGER NOT NULL DEFAULT 0 CHECK (cost_minor >= 0) |
| `price_minor` | INTEGER NOT NULL CHECK (price_minor >= 0) |
| `tax_exempt` | INTEGER NOT NULL DEFAULT 0 |
| `min_stock_milli` | INTEGER NOT NULL DEFAULT 0 CHECK (min_stock_milli >= 0) |
| `archived` | INTEGER NOT NULL DEFAULT 0 |
| `created_at` | TEXT NOT NULL DEFAULT (datetime('now','localtime')) |

### `branch_stock`

| Column | Definition |
|---|---|
| `branch_id` | INTEGER NOT NULL REFERENCES branches(id) |
| `product_id` | INTEGER NOT NULL REFERENCES products(id) |
| `qty_milli` | INTEGER NOT NULL DEFAULT 0 |
| *constraint* | `PRIMARY KEY (branch_id, product_id)` |

### `purchases`

| Column | Definition |
|---|---|
| `id` | INTEGER PRIMARY KEY AUTOINCREMENT |
| `branch_id` | INTEGER NOT NULL REFERENCES branches(id) |
| `supplier_id` | INTEGER NOT NULL REFERENCES suppliers(id) |
| `po_number` | TEXT NOT NULL UNIQUE |
| `invoice_number` | TEXT |
| `status` | TEXT NOT NULL DEFAULT 'ORDERED' CHECK (status IN ('DRAFT','ORDERED','PARTIAL','RECEIVED','CANCELLED')) |
| `total_minor` | INTEGER NOT NULL DEFAULT 0 |
| `paid_minor` | INTEGER NOT NULL DEFAULT 0 |
| `ordered_at` | TEXT NOT NULL DEFAULT (datetime('now','localtime')) |
| `received_at` | TEXT |
| `created_by` | INTEGER REFERENCES users(id) |

### `purchase_items`

| Column | Definition |
|---|---|
| `id` | INTEGER PRIMARY KEY AUTOINCREMENT |
| `purchase_id` | INTEGER NOT NULL REFERENCES purchases(id) ON DELETE CASCADE |
| `product_id` | INTEGER NOT NULL REFERENCES products(id) |
| `qty_milli` | INTEGER NOT NULL CHECK (qty_milli > 0) |
| `received_milli` | INTEGER NOT NULL DEFAULT 0 |
| `unit_cost_minor` | INTEGER NOT NULL CHECK (unit_cost_minor >= 0) |

### `cash_shifts`

| Column | Definition |
|---|---|
| `id` | INTEGER PRIMARY KEY AUTOINCREMENT |
| `branch_id` | INTEGER NOT NULL REFERENCES branches(id) |
| `user_id` | INTEGER NOT NULL REFERENCES users(id) |
| `opened_at` | TEXT NOT NULL DEFAULT (datetime('now','localtime')) |
| `closed_at` | TEXT |
| `opening_minor` | INTEGER NOT NULL DEFAULT 0 |
| `expected_minor` | INTEGER |
| `counted_minor` | INTEGER |
| `variance_minor` | INTEGER |
| `status` | TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','CLOSED')) |
| `notes` | TEXT |

### `sales`

| Column | Definition |
|---|---|
| `id` | INTEGER PRIMARY KEY AUTOINCREMENT |
| `branch_id` | INTEGER NOT NULL REFERENCES branches(id) |
| `receipt_no` | TEXT NOT NULL UNIQUE |
| `customer_id` | INTEGER REFERENCES customers(id) |
| `cashier_id` | INTEGER NOT NULL REFERENCES users(id) |
| `shift_id` | INTEGER REFERENCES cash_shifts(id) |
| `subtotal_minor` | INTEGER NOT NULL |
| `discount_minor` | INTEGER NOT NULL DEFAULT 0 |
| `tax_minor` | INTEGER NOT NULL DEFAULT 0 |
| `total_minor` | INTEGER NOT NULL |
| `cogs_minor` | INTEGER NOT NULL DEFAULT 0 |
| `status` | TEXT NOT NULL DEFAULT 'COMPLETED' CHECK (status IN ('COMPLETED','VOIDED','PARTIALLY_REFUNDED','REFUNDED')) |
| `created_at` | TEXT NOT NULL DEFAULT (datetime('now','localtime')) |
| `voided_at` | TEXT |
| `voided_by` | INTEGER REFERENCES users(id) |
| `void_reason` | TEXT |

### `sale_items`

| Column | Definition |
|---|---|
| `id` | INTEGER PRIMARY KEY AUTOINCREMENT |
| `sale_id` | INTEGER NOT NULL REFERENCES sales(id) |
| `product_id` | INTEGER NOT NULL REFERENCES products(id) |
| `description` | TEXT NOT NULL |
| `qty_milli` | INTEGER NOT NULL CHECK (qty_milli > 0) |
| `unit_price_minor` | INTEGER NOT NULL CHECK (unit_price_minor >= 0) |
| `unit_cost_minor` | INTEGER NOT NULL DEFAULT 0 |
| `discount_minor` | INTEGER NOT NULL DEFAULT 0 |
| `tax_minor` | INTEGER NOT NULL DEFAULT 0 |
| `line_total_minor` | INTEGER NOT NULL |
| `refunded_milli` | INTEGER NOT NULL DEFAULT 0 |

### `payments`

| Column | Definition |
|---|---|
| `id` | INTEGER PRIMARY KEY AUTOINCREMENT |
| `sale_id` | INTEGER REFERENCES sales(id) |
| `customer_id` | INTEGER REFERENCES customers(id) |
| `purchase_id` | INTEGER REFERENCES purchases(id) |
| `shift_id` | INTEGER REFERENCES cash_shifts(id) |
| `method` | TEXT NOT NULL |
| `direction` | TEXT NOT NULL DEFAULT 'IN' CHECK (direction IN ('IN','OUT')) |
| `amount_minor` | INTEGER NOT NULL CHECK (amount_minor > 0) |
| `tendered_minor` | INTEGER |
| `change_minor` | INTEGER NOT NULL DEFAULT 0 |
| `reference` | TEXT |
| `created_at` | TEXT NOT NULL DEFAULT (datetime('now','localtime')) |

### `refunds`

| Column | Definition |
|---|---|
| `id` | INTEGER PRIMARY KEY AUTOINCREMENT |
| `sale_id` | INTEGER NOT NULL REFERENCES sales(id) |
| `sale_item_id` | INTEGER REFERENCES sale_items(id) |
| `qty_milli` | INTEGER NOT NULL CHECK (qty_milli > 0) |
| `amount_minor` | INTEGER NOT NULL CHECK (amount_minor >= 0) |
| `tax_minor` | INTEGER NOT NULL DEFAULT 0 |
| `cogs_minor` | INTEGER NOT NULL DEFAULT 0 |
| `method` | TEXT NOT NULL |
| `restock` | INTEGER NOT NULL DEFAULT 1 |
| `reason` | TEXT NOT NULL |
| `approved_by` | INTEGER NOT NULL REFERENCES users(id) |
| `created_at` | TEXT NOT NULL DEFAULT (datetime('now','localtime')) |

### `expenses`

| Column | Definition |
|---|---|
| `id` | INTEGER PRIMARY KEY AUTOINCREMENT |
| `branch_id` | INTEGER REFERENCES branches(id) |
| `category` | TEXT NOT NULL |
| `description` | TEXT |
| `amount_minor` | INTEGER NOT NULL CHECK (amount_minor > 0) |
| `method` | TEXT NOT NULL DEFAULT 'Cash' |
| `incurred_on` | TEXT NOT NULL |
| `created_by` | INTEGER REFERENCES users(id) |
| `created_at` | TEXT NOT NULL DEFAULT (datetime('now','localtime')) |

### `stock_movements`

| Column | Definition |
|---|---|
| `id` | INTEGER PRIMARY KEY AUTOINCREMENT |
| `branch_id` | INTEGER NOT NULL REFERENCES branches(id) |
| `product_id` | INTEGER NOT NULL REFERENCES products(id) |
| `type` | TEXT NOT NULL CHECK (type IN ('SALE','REFUND','VOID','PURCHASE','ADJUSTMENT','TRANSFER_OUT','TRANSFER_IN','OPENING')) |
| `qty_milli` | INTEGER NOT NULL |
| `balance_milli` | INTEGER NOT NULL |
| `ref_type` | TEXT |
| `ref_id` | INTEGER |
| `note` | TEXT |
| `user_id` | INTEGER REFERENCES users(id) |
| `created_at` | TEXT NOT NULL DEFAULT (datetime('now','localtime')) |

### `stock_transfers`

| Column | Definition |
|---|---|
| `id` | INTEGER PRIMARY KEY AUTOINCREMENT |
| `from_branch_id` | INTEGER NOT NULL REFERENCES branches(id) |
| `to_branch_id` | INTEGER NOT NULL REFERENCES branches(id) |
| `product_id` | INTEGER NOT NULL REFERENCES products(id) |
| `qty_milli` | INTEGER NOT NULL CHECK (qty_milli > 0) |
| `status` | TEXT NOT NULL DEFAULT 'COMPLETED' |
| `created_by` | INTEGER REFERENCES users(id) |
| `created_at` | TEXT NOT NULL DEFAULT (datetime('now','localtime')) |
| *constraint* | `CHECK (from_branch_id <> to_branch_id)` |

### `business_settings`

| Column | Definition |
|---|---|
| `key` | TEXT PRIMARY KEY |
| `value` | TEXT NOT NULL |
| `updated_at` | TEXT NOT NULL DEFAULT (datetime('now','localtime')) |

### `audit_logs`

| Column | Definition |
|---|---|
| `id` | INTEGER PRIMARY KEY AUTOINCREMENT |
| `user_id` | INTEGER REFERENCES users(id) |
| `username` | TEXT |
| `action` | TEXT NOT NULL |
| `entity` | TEXT |
| `entity_id` | TEXT |
| `details` | TEXT |
| `created_at` | TEXT NOT NULL DEFAULT (datetime('now','localtime')) |

### `licences`

| Column | Definition |
|---|---|
| `id` | INTEGER PRIMARY KEY AUTOINCREMENT |
| `licence_id` | TEXT NOT NULL UNIQUE |
| `edition` | TEXT NOT NULL CHECK (edition IN ('STARTER','PROFESSIONAL','ENTERPRISE')) |
| `kind` | TEXT NOT NULL CHECK (kind IN ('TRIAL','PERPETUAL','ANNUAL','MONTHLY')) |
| `licensee` | TEXT NOT NULL |
| `max_devices` | INTEGER NOT NULL DEFAULT 1 |
| `issued_at` | TEXT NOT NULL |
| `expires_at` | TEXT |
| `grace_days` | INTEGER NOT NULL DEFAULT 14 |
| `token` | TEXT NOT NULL |
| `installed_at` | TEXT NOT NULL DEFAULT (datetime('now','localtime')) |

### `licence_activations`

| Column | Definition |
|---|---|
| `id` | INTEGER PRIMARY KEY AUTOINCREMENT |
| `licence_id` | TEXT NOT NULL REFERENCES licences(licence_id) |
| `device_id` | TEXT NOT NULL |
| `activated_at` | TEXT NOT NULL DEFAULT (datetime('now','localtime')) |
| `last_check_at` | TEXT |
| *constraint* | `UNIQUE (licence_id, device_id)` |

## Indexes

- `idx_products_name` on `products(name)`
- `idx_products_category` on `products(category_id)`
- `idx_sales_created` on `sales(created_at)`
- `idx_sales_cashier` on `sales(cashier_id)`
- `idx_sales_customer` on `sales(customer_id)`
- `idx_sale_items_sale` on `sale_items(sale_id)`
- `idx_sale_items_product` on `sale_items(product_id)`
- `idx_payments_sale` on `payments(sale_id)`
- `idx_expenses_date` on `expenses(incurred_on)`
- `idx_stock_mov_product` on `stock_movements(product_id, created_at)`
- `idx_audit_created` on `audit_logs(created_at)`
- `idx_audit_user` on `audit_logs(user_id)`
