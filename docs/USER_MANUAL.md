# BizFlow Pro user manual

## Signing in
Enter your username and password. After 5 wrong attempts the account locks for 15 minutes. Forgot your password? Click **Forgot password?** and use a one-time code from your administrator (People & Access > User accounts > *Issue recovery code*). You are signed out automatically after a period of inactivity (default 30 minutes, adjustable).

## Menu
You only see the screens your role allows. Roles: **Administrator** (everything), **Manager**, **Cashier**, **Inventory officer**, **Accountant**. Administrators can fine-tune each role under People & Access > Role permissions. Cashiers by default cannot see financial reports, manage employees or change settings.

## Selling (Point of Sale)
1. **Open a cash shift** (counted opening float) if prompted.
2. Scan a barcode, or type a name/SKU and press Enter. Double-click a product to add it. Use **+ / - / Set quantity / Remove** for the cart.
3. Discounts: per line (**Line discount**) or for the whole sale (amount, or tick **%**). Cashiers can give up to the limit set in Settings; larger discounts need a manager.
4. Optionally choose a customer (needed for credit sales).
5. **Charge / Pay**. Pick the method (Cash, EcoCash, Bank Transfer, Card, or Customer Credit). Use **+ Split payment** to combine methods. Only cash can give change.
6. The receipt is shown; **Print receipt** uses your printer (58mm/80mm thermal or A4, set in Settings > Receipts).
Prices always come from the product list at the moment of sale. If stock is short the sale is refused and nothing is saved.

## Sales history, refunds and voids
Sales History lists sales (cashiers see their own; managers see all). **View / reprint receipt**, **Refund** (choose items and quantities, reason required, optionally return to stock) or **Void sale** (reverses the whole sale). Nothing is ever deleted: the sale stays, marked REFUNDED / VOIDED, and the action is recorded in the audit log.

## Inventory
Add products with SKU (auto-generated if empty), barcode, category, purchase and selling price, minimum stock, image and opening stock. **Archive** instead of deleting. **Adjust stock** needs a reason. **Stock history** shows every movement. Low-stock products are highlighted and appear in the bell menu.

## Suppliers & purchasing
Create suppliers and **purchase orders**. When goods arrive choose **Receive goods**: stock increases and the product's purchase price follows the latest order. Record payments with **Pay supplier**; the supplier's balance shows what you owe.

## Customers
Name, phone, email, address, purchase history. Managers can enable **credit** with a limit; a credit sale adds to the balance, **Receive payment** reduces it, **Statement** shows the running account. Financial details are hidden from roles without the permission.

## Finance
Revenue, cost of goods, gross profit, expenses, net profit and cash received are shown separately:
- **Revenue** = sales excluding tax, minus refunds.
- **Gross profit** = revenue - cost of goods sold.
- **Net profit** = gross profit - expenses.
- **Cash received** = money actually collected (credit sales are not cash until paid).
Record expenses by category; deleting one needs a reason.

## Shifts
Open a shift with the cash in the drawer; close it by counting. The system shows the expected cash (float + cash sales - cash refunds) and the difference. Managers review all shifts.

## Reports
Choose a report and date range, **Run**, then **Export PDF** or **Export Excel** (if your edition and role allow). Reports include the business name and details.

## Backup & Restore
**Back up now** saves a verified copy to the backup folder (choose an external drive with *Choose backup folder*). **Schedule** sets automatic backups. **Restore** replaces current data with a chosen backup but first saves your current data as a safety copy; you are signed out afterwards. Damaged backups are flagged.

## Settings
Business information, currency and tax, receipts, discounts/payment methods/session timeout, theme (light/dark), database info, update checks, and licence.
