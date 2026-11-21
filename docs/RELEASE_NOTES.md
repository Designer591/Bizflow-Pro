# Release notes

## 1.0.0 (first release candidate)
**Included:** setup wizard; sign-in with BCrypt hashing, lockout, recovery codes, roles with 30 granular permissions, audit log; dashboard with date filters; POS with barcode search, discounts, tax, split payments, receipts (58mm/80mm/A4), refunds and voids; inventory with movements, adjustments, transfers, archiving; suppliers, purchase orders, goods received; customers with credit accounts and statements; expenses and profit summaries; cashier shifts and reconciliation; 14 reports with PDF/Excel export; verified backups with scheduled runs and safe restore; signed offline licences plus separate licence server; light/dark theme.

**Release blockers before shipping to customers:**
1. Run `mvn test` on a machine with Maven Central access and fix anything the sandbox could not check (JavaFX UI compile, BCrypt, POI).
2. Walk through every screen manually on Windows.
3. Create the licence key pair and rebuild.
4. Build and test the installer on a clean Windows machine.

Direct ESC/POS receipt printing and cash-drawer pulse are included (try it on your printer model first).

**Not in 1.0.0:** central multi-branch backend, licence admin console, automatic update installation.
