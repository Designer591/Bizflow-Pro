<#
  Builds the BizFlow Pro Windows installer (.exe) with jpackage.
  Run on WINDOWS (jpackage cannot create a Windows installer from Linux/macOS).

  One-time prerequisites on the build machine:
    * JDK 21 (full JDK, with jpackage)          https://adoptium.net
    * Apache Maven 3.9+
    * WiX Toolset 3.x or 4+ on PATH (jpackage uses it to make .exe/.msi)   https://wixtoolset.org

  Usage (PowerShell, from the repository root):
    .\installer\build-installer.ps1 -Version 1.0.0

  Customers do NOT need Java or Maven: the installer bundles its own Java runtime and JavaFX.
#>
param([string]$Version = "1.0.0")
$ErrorActionPreference = "Stop"
Set-Location (Resolve-Path "$PSScriptRoot\..")

if (-not (Test-Path "desktop\src\main\resources\licence-public-key.txt")) {
    Write-Warning "desktop\src\main\resources\licence-public-key.txt is missing. This build will only run in 14-day evaluation mode. See docs\LICENSING.md to create the key pair."
}

Write-Host "== Running all tests and building =="
mvn -B clean package
if ($LASTEXITCODE -ne 0) { throw "Maven build or tests failed. Installer not created." }

$libs = "desktop\target\libs"
$mainJar = "bizflow-desktop-1.0.0.jar"
Copy-Item "desktop\target\$mainJar" $libs -Force
if (Test-Path "installer\dist") { Remove-Item "installer\dist" -Recurse -Force }

Write-Host "== Creating installer with jpackage =="
jpackage `
  --type exe `
  --name "BizFlow Pro" `
  --app-version $Version `
  --vendor "EROLITECHNOLOGIES" `
  --description "BizFlow Pro - Business Management Made Simple" `
  --copyright "EROLITECHNOLOGIES" `
  --input $libs `
  --main-jar $mainJar `
  --main-class com.erolitechnologies.bizflow.Launcher `
  --icon "installer\app.ico" `
  --dest "installer\dist" `
  --add-modules "java.base,java.desktop,java.logging,java.naming,java.net.http,java.prefs,java.sql,java.xml,java.management,java.security.jgss,jdk.unsupported,jdk.crypto.ec,jdk.httpserver" `
  --java-options "-Dfile.encoding=UTF-8" `
  --java-options "-Xmx512m" `
  --win-menu --win-menu-group "EROLITECHNOLOGIES" `
  --win-shortcut `
  --win-dir-chooser `
  --win-upgrade-uuid "7b0f3c52-4c0e-4f6e-9d2a-5d7a1c8e41b9"
if ($LASTEXITCODE -ne 0) { throw "jpackage failed." }

Write-Host ""
Write-Host "Installer created in installer\dist"
Write-Host "Customer data lives in %APPDATA%\BizFlowPro and is NOT removed by uninstalling."
