# Installation guide

## For customers (Windows 10 / 11, 64-bit)
1. Run `BizFlow Pro-<version>.exe`. Java is included; you do not install anything else.
2. Choose the install folder (default is fine) and finish. A desktop shortcut and a Start menu entry (EROLITECHNOLOGIES > BizFlow Pro) are created.
3. Open BizFlow Pro. On first launch the **setup wizard** asks for your business details, currency and tax, receipt style, and creates the administrator account. You can load sample products to try the system.
4. Sign in. Without a licence the app runs in a 14-day evaluation. Enter your licence in **Settings > Licence**.

### Where your data lives
`%APPDATA%\BizFlowPro\` contains `data\bizflow.db` (your business data), `logs\`, `backups\` and `assets\` (logo, product images). **Uninstalling does not delete this folder.** Back it up (see the user manual).

### Upgrading
Run the newer installer over the old one. Your data is kept; database changes are applied automatically at the next start.

### Uninstalling
Windows Settings > Apps > BizFlow Pro > Uninstall.

## For the build machine (EROLITECHNOLOGIES)
Requirements: Windows, JDK 21 (with `jpackage`), Maven 3.9+, WiX Toolset on PATH.
1. Create the licence key pair once (see `docs/LICENSING.md`) and put `licence-public-key.txt` in `desktop/src/main/resources/`.
2. `.\installer\build-installer.ps1 -Version 1.0.0` runs all tests, builds, and writes the installer to `installer\dist\`.
3. Test the installer on a clean Windows machine (install, first run, sale, backup, uninstall) before delivering it.

## Offline recovery of a locked-out administrator
On the customer's computer, from the install folder: `"BizFlow Pro.exe" --recovery-code <username>` prints a one-time code (valid 30 minutes) for **Forgot password?** on the sign-in screen.
