# BizFlow Pro
**Business Management Made Simple.** *Powered by EROLITECHNOLOGIES.*

All-in-one business management and point-of-sale for supermarkets, groceries, boutiques, hardware and electronics shops, wholesalers and general retail. Java 21, JavaFX, SQLite (offline by default).

## What is in this repository

| Module | Purpose |
|---|---|
| `desktop/` | The Windows desktop application (JavaFX UI, services, SQLite database, reports, backup) |
| `licensing-core/` | Licence data format and **public-key verification** (shipped inside the desktop app) |
| `licence-server/` | Licence **signing** service and HTTPS API. Holds the private key. **Never shipped to customers** |
| `installer/` | `build-installer.ps1` (jpackage, Windows) and the app icon |
| `docs/` | Installation, user manual, developer, database, licensing, troubleshooting, release notes |

## Quick start (developers)
```
mvn test                 # unit + integration tests (needs Maven Central access for dependencies)
mvn -pl desktop javafx:run
```
Customer installer (on Windows, JDK 21 + Maven + WiX Toolset): `.\installer\build-installer.ps1`

## Verification status - read this before selling it
Last full run in the sandbox: **101 tests, 0 failures** (SQLite via a test bridge, JUnit via a stand-in runner, because Maven Central was unreachable).

This was built in a sandbox that could not download Maven dependencies (JavaFX, BCrypt, POI, ...) and has no Windows or display. Being precise about what that means:

| Area | Status |
|---|---|
| Money, tax (inclusive/exclusive), discounts, split payments, change | **Tested** (16 unit tests) |
| Checkout transaction, rollback, stock deduction, concurrent oversell, refunds, voids, cash shifts, credit limits, permissions | **Tested** against real SQLite (integration tests) |
| Authentication, lockout, password recovery, user and role management | **Tested** |
| Inventory, stock adjustment/transfer, purchasing and goods received | **Tested** |
| Backup, restore (with safety copy), checksum validation | **Tested** |
| Licence signing/verification, expiry, grace, device limits, clock rollback, licence server API | **Tested** |
| Reports, receipts (58mm/80mm/A4 widths), PDF writer | **Tested** |
| Database schema and migrations | **Tested** (applies cleanly; all 24 required tables present) |
| JavaFX screens (all 13 navigation items, login, setup wizard, licence gate) | **Written, NOT compiled or run.** Service calls were statically checked against the real method signatures; UI compile errors are still possible |
| BCrypt hashing (`BcryptPasswordHasher`) | Written; tests used a stand-in hasher. Needs `mvn test` with real dependency |
| Excel export (Apache POI) | Written, not run |
| Windows installer | Script written, **not run** (needs Windows + WiX) |
| MySQL centralised multi-branch edition | **Not implemented.** Branch data model, branch-scoped stock/sales and transfers exist in SQLite; the central server/sync is described in `docs/DEVELOPER.md` |
| ESC/POS thermal printing | Command bytes **tested**; sending to a physical printer is **untested** (no printer here). Falls back to the normal print dialog when switched off |
| JasperReports, HikariCP, MySQL driver | **Removed** from the build (PDFs use the built-in `PdfWriter`; SQLite uses short-lived connections). Add them back when the server edition is built |

**First thing to do on your own machine:** run `mvn test`, then `mvn -pl desktop javafx:run`, and send any compile or runtime error back to be fixed.

## Licensing in one paragraph
Licences are Ed25519-signed. The desktop app contains only the public key (`desktop/src/main/resources/licence-public-key.txt`); the private key stays on the licence server. There are no universal activation codes. Without a key file the app runs in a 14-day evaluation mode. See `docs/LICENSING.md`.
