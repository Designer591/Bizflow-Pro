# Troubleshooting

| Problem | What to do |
|---|---|
| "Open your cash shift before making sales" | Open a shift (Point of Sale banner or Shifts screen). Administrators can switch this rule off in Settings > Sales & security. |
| "Not enough stock for ..." | The sale was refused and nothing was saved. Receive stock (Suppliers & Purchasing) or adjust stock with a reason. |
| Forgot password | Another administrator: People & Access > Issue recovery code. Only administrator locked out: on the PC run `"BizFlow Pro.exe" --recovery-code <username>`, then use *Forgot password?*. |
| Account locked | Wait 15 minutes, or an administrator sets a new password. |
| "The database is busy" | Another program (e.g. a backup tool or antivirus) is holding the file. Retry; exclude `%APPDATA%\BizFlowPro` from live sync tools such as OneDrive. |
| "BizFlow Pro could not open its database" | Do not delete anything. Send `%APPDATA%\BizFlowPro\logs` to support. Restore the latest verified backup if directed. |
| Backup says DAMAGED | Do not restore it. Use an older verified backup; set a new backup folder on a healthy drive. |
| "Licence required" | Your licence expired past its grace period or is invalid. Your data is untouched. Settings > Licence (or sign in as an administrator) to renew. |
| "system clock appears to have been set back" | Correct the Windows date and time. |
| Cannot reach licence server | Check internet, or use offline activation (see LICENSING.md). |
| Receipt prints cut off | Settings > Receipts: pick the paper width matching the printer (58mm/80mm/A4) and set the same paper size in the Windows printer settings. |
| Direct thermal printing does nothing / garbled | Settings > Receipts: pick the printer by its Windows name and use **Print test receipt**. If it still fails, untick direct printing to use the normal print dialog. |
| Barcode scanner types but nothing happens | Click the search box first; the scanner must send Enter after the code (default for most scanners). |

Logs: `%APPDATA%\BizFlowPro\logs\bizflow-0.log`. Include the time the problem happened when contacting support.
