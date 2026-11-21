# Developer documentation

## Architecture (layers)
```
ui/          JavaFX views (Presentation). Never touch SQL. Receive AppContext.
service/     Application services: permission checks, validation, transactions, audit.
domain/      Pure business logic with no I/O: Money, SaleCalculator, PaymentAllocator.
security/    Roles, permissions, Session, password hashing.
infra/       Db (JDBC, transactions), Migrator, AppPaths, Log, exceptions.
report/      Table model, PdfWriter, ExcelExporter.
```
There is no separate repository layer: services use small JDBC helpers in `Db` (prepared statements only). Keeping SQL in services keeps each transaction readable in one place; extract repositories if a service grows beyond ~200 lines.

Wiring: `AppContext` builds every service once. `App` (JavaFX) opens the DB, runs migrations, seeds roles, shows login or the setup wizard.

## Rules that must not be broken
1. **Money = BigDecimal**, stored as integer minor units. Quantities stored as thousandths.
2. **Every financial operation is one `Db.tx(...)`.** Any exception rolls back everything.
3. **Stock changes only through `StockService.apply`** (atomic conditional UPDATE + movement row). This is what makes concurrent sales safe.
4. **Prices come from the database at checkout**, never from the screen.
5. **Never delete completed transactions.** Refund/void create reversing records.
6. Every service method starts with `session.require(Permission.X)`. UI hiding is a convenience, not security.
7. Use `AppException` for messages shown to users; anything else is logged and shown generically.
8. No passwords, licence keys or customer data in logs or audit details.

## Tests
`mvn test` runs: calculator/payment unit tests, licence tests, and integration tests against a real temp SQLite database (checkout, concurrency, refunds, auth, inventory, finance/reports, backup, licensing). Tests use `TestEnv` which creates a migrated, set-up database. `TestEnv` uses a fast stand-in password hasher; add a dedicated test for `BcryptPasswordHasher` if you change hashing.

## Database
See `DATABASE.md`. Add `V2__*.sql` migrations and list them in `db/migration/index.txt`.

## Multi-branch (Enterprise) - what exists and what does not
Exists: `branches`, per-branch stock (`branch_stock`), branch-scoped sales/shifts/users, stock transfers (`StockService.transfer`), branch management UI, Enterprise feature flag.
**Not built:** the central synchronised backend. Recommended design: a hosted API service (HTTPS, token auth per branch) in front of MySQL; desktop clients never receive database credentials and never connect to MySQL directly. Branches keep working offline on SQLite and push/pull events (sales, stock movements, transfers) with idempotency keys; consolidated reports run on the server. Use HikariCP in the server only.

## Known gaps / honest backlog
- JavaFX UI has not been compiled in CI yet; first compile may reveal typos.
- `jasperreports`, `HikariCP`, `mysql-connector-j` were removed from the desktop pom; add them back only when the server edition needs them.
- No automatic installer-based updater (update *check* exists; installation is by running the new installer).
- Direct ESC/POS printing (Settings > Receipts) is implemented in `infra/EscPosPrinter` and its bytes are unit-tested, but it has not been tried on a real printer; text is sent as plain ASCII.
- Licence server has no admin GUI.
