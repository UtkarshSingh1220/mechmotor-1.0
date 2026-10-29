# MechMotor 1.0 — Low-Level Design

## Package layout
- `com.mechmotor`: JavaFX entry point and Spring lifecycle.
- `com.mechmotor.config`: local SQLite datasource and configuration.
- `com.mechmotor.domain`: immutable domain records.
- `com.mechmotor.repository`: JDBC queries.
- `com.mechmotor.service`: business services.
- `src/main/resources/schema.sql`: schema and indexes.
- `src/test/java`: unit and integration tests.

## Local database
Default path: `%LOCALAPPDATA%/MechMotor/data/mechmotor.db` on Windows. Override with `-Dmechmotor.dataDir=<path>` for tests or managed deployments. Keep the database out of the installation directory so upgrades do not overwrite user data.

## Inventory persistence and search
- `PartRepository` supports create, update, soft-deactivate, details lookup and paged search.
- `InventoryFilter` validates and bounds page size, quantity/price ranges and sort keys. Only allowlisted SQL expressions are used for ordering.
- The free-text query is normalized with `Locale.ROOT`, then bound to prepared statements. It searches SKU, barcode, part number, name, brand, company, material, color, category and supplier; exact SKU/barcode/part-number matches are ranked first.
- Literal `%`, `_` and `!` are escaped for substring matching with SQL `ESCAPE '!'`.
- Structured filters include brand, material, color, category, supplier, stock status, quantity bounds and selling-price bounds. Separate filter groups are combined with AND; the free-text alternatives are combined with OR.
- Search results use a count query and bounded LIMIT/OFFSET pagination (UI page size 50, hard maximum 200).
- CSV export streams all active rows matching the currently applied inventory filters in bounded 200-row pages; output includes quantity, reorder level and both prices, uses UTF-8 with a BOM, CSV quote escaping and formula-injection mitigation.
- CSV import is create-only, limited to 25 MB and 20,000 rows, validates required headers/values and category/supplier references before writes, and inserts the batch in a single transaction. Existing SKUs are never overwritten; any conflict rolls back the whole batch. Fitment associations are managed separately.
- The dashboard aggregates active part count, total units, low-stock count, out-of-stock count and estimated retail value in one SQL query; values are refreshed after inventory page loads. Retail value is a selling-price estimate, not an accounting valuation.
- Saved inventory views persist the search, structured filters, stock status, price/quantity bounds and sorting; pagination is intentionally reset when a view is loaded.
- Vehicle fitments are stored per part with make/model, year range, optional engine/trim and notes. Free-text inventory search checks these fields and years within the fitment range through a correlated EXISTS query. Fitment data is added through additive schema creation; backup restore applies the current idempotent schema to a staged copy before validation.
- Create/edit/deactivate operations are exposed through the service layer. Create/edit operations are transactional; stock quantity changes produce `ADJUSTMENT` rows in `stock_movements`. Deactivation is a soft update and retains history.
- Category lookup uses a recursive CTE to display nested paths. Root/subcategory creation, category rename and guarded soft-deactivation use the existing categories parent_id and active fields; active child categories or active inventory references prevent deactivation. Supplier lookup/creation use the existing schema; no schema change was required for this milestone. Supplier creation normalizes whitespace and reuses an existing name case-insensitively to avoid accidental duplicates from repeated submissions. Supplier records can be viewed and edited for name, phone and unique supplier code without changing supplier IDs, preserving part references.

SQLite's built-in `lower()` is not complete Unicode case folding. If multilingual case folding becomes a requirement, use a tested normalization policy or a suitable FTS/ICU strategy. Leading-wildcard substring queries will not use ordinary B-tree indexes efficiently; benchmark and add FTS5 when warranted.

## Threading
Never run JDBC, report generation or Gmail operations on the JavaFX Application Thread. The desktop shell uses a single daemon database executor for bounded sequential work and JavaFX Tasks to return results to the UI thread. Future concurrent workloads must retain a bounded execution policy and stale-result handling.

## Activation key design

- `LicenseService` verifies `MM1.<base64url-payload>.<base64url-signature>` tokens using Ed25519 and the embedded public verification key.
- Signed payload fields are version, license ID, customer, issued epoch day, expiry epoch day and comma-separated features. The license must include `INVENTORY`; unknown features, future issue dates, expired keys and invalid signatures are rejected.
- The activation token is stored atomically at `%LOCALAPPDATA%/MechMotor/license/activation.key`; fallback is `~/.mechmotor/license/activation.key`. JVM property `-Dmechmotor.licenseFile` overrides the path.
- The desktop header has **Activation…**. It displays license status, customer, license ID, expiry and features. The stored token is masked until the user selects **Reveal stored activation key**.
- A valid activation is required before inventory data loads. An invalid/expired key does not delete the key or inventory; the activation dialog remains available to replace it.
- `tools/license-issuer/IssueLicense.java` signs tokens using an external PKCS#8 Ed25519 private-key PEM. Never commit or package the issuer private key. Keep it offline and backed up securely.
- Inject `Clock` in tests to make issue/expiry behavior deterministic. Local signing verification is not protection against a privileged attacker who patches the executable.


## Sales checkout

- Checkout supports a cart of up to 100 distinct parts in one sale. Each line snapshots SKU/name, quantity and unit price; the sale header stores subtotal, discount and total.
- The service pre-validates stock and overflow-safe totals, then inserts the sale, each item snapshot, guarded stock deduction and negative SALE movement in a single TransactionTemplate.
- A stock conflict or any later write failure rolls back all cart lines, stock deductions, ledger movements and payment. The database enforces unique sale numbers.
- Current payment is full-payment only (CASH, UPI, CARD or BANK_TRANSFER). Supplier-payment tracking is separate and supports partial payments against purchase payables.
- Sale voids, per-item partial returns, discount-aware refund rounding and PDF invoices are implemented in separate audited workflows. Refund records are bookkeeping only; the application does not transfer money through a bank, UPI app or card terminal.

## Sale voids and refund audit

- `SalesService.voidSale` voids a posted sale inside a transaction, restores each item quantity with overflow protection, writes positive `REVERSAL` stock movements, and records a unique `sale_voids` audit row.
- Repeated void requests are rejected; a stock-ledger failure rolls back the sale status, inventory and void record together.
- The app records the full refund amount and original payment method but does not initiate a real bank, UPI or card refund. Staff must process that separately and retain the refund reference.

## Sales invoice PDFs

- `InvoiceService.exportSaleInvoice` queries the original sale and item snapshots, payments, partial-return totals and full-void audit.
- The generated PDF uses the immutable sale snapshot rather than current part names/prices. It includes status, subtotal, discount, payment/refund records and a manual-settlement disclaimer.
- Output is written to a temporary file and moved to the requested destination only after generation succeeds. Existing invoice files are not intentionally overwritten.
- PDFBox uses its standard fonts; text is normalized to printable ASCII and currency is formatted as INR to avoid missing glyphs.

## Sales returns and refunds

- `SalesService.returnableSale` lists each item line on a posted sale and calculates each line's net total after a deterministic proportional allocation of the sale-level discount.
- `SalesService.returnSale` records a return, restores inventory and writes a positive `RETURN` stock movement inside one transaction.
- Return quantity and refund history are tracked per sale item. Cumulative integer rounding ensures returning every unit refunds exactly that line's net total without exceeding the sale total.
- Full-sale void is rejected after any partial return. Refunds are audit records only; actual cash/UPI/card settlement is external.

## Purchase receiving

- `PurchasingService` posts one-item and multi-item supplier receipts inside a `TransactionTemplate`.
- The receipt snapshots supplier name, SKU/name, quantity and unit cost, adds received units with an overflow-guarded upsert, updates the part's latest purchase cost, and writes a positive `PURCHASE` stock movement.
- The purchase header, item, inventory update and stock ledger share one transaction. Any failure rolls back the complete receipt.
- `FormalPurchaseOrderDraft` validates supplier, optional ISO expected date, notes and up to 100 distinct item lines; money remains integer paise.
- `purchase_orders` and `purchase_order_items` are added by migration V4. Creating an order snapshots supplier and item details without changing stock. Order states are `ORDERED`, `RECEIVED`, and `CANCELLED`; cancellation requires a reason and is allowed only before receipt.
- `PurchasingService.receivePurchaseOrder` accepts one or more partial deliveries, validates each line against the remaining ordered quantity, creates linked supplier receipts, updates stock/cost/ledger atomically and transitions the order to `PARTIALLY_RECEIVED` or `RECEIVED` as appropriate. Over-receipt and receipt cancellation that would invalidate received stock are rejected.
- Supplier payments are recorded against purchase payables with duplicate-reference and overpayment safeguards; voided payments are excluded from the payable report.
- Supplier returns are linked to original receipt lines and validate remaining returnable quantity and available stock before posting stock/ledger/payable changes. A return total above the current outstanding payable is rejected before writes to avoid an untracked supplier-credit balance. Credit carry-forward/settlement is not implemented; actual supplier settlement remains external.

## Build and test
- `mvn clean test`
- `mvn javafx:run`
- `mvn dependency:tree`

## Windows packaging
Package only after validating the JavaFX native libraries, launcher configuration and dependency layout. Use `jpackage --type exe` on Windows; test install, offline launch, upgrade and uninstall on a clean machine. Installer generation may require WiX tooling depending on JDK packaging support.

## Operational report exports

- `OperationalReportsService` exports sales, supplier receipt, stock-ledger, and supplier payable reconciliation rows for an inclusive date range.
- The report range is validated and bounded to 366 days; result rows are ordered by timestamp and stable database ID.
- Money is formatted from integer paise. CSV values are quoted and spreadsheet-formula mitigation is applied to text values.
- Output is generated in a temporary file before publication; an existing destination is rejected rather than silently overwritten.
- The JavaFX report picker parses dates before scheduling the export on the existing background database executor. Export requires the existing `CSV` feature entitlement.
- The supplier payable report shows each purchase's payable total, posted supplier payments, and arithmetic balance; voided payment entries are excluded and cancelled receipts contribute zero payable value. A negative balance is retained as a reconciliation signal rather than hidden.
- Reports are operational exports, not accounting/tax certification. Recorded sale or supplier payment entries are not proof that an external payment settled.


## Current architecture diagram set (2026-10-29)

The maintained source-aligned diagrams and traceable functional/non-functional requirements are in **[Architechture Design](../Architechture%20Design/README.md)**:

- [Requirements matrix](../Architechture%20Design/REQUIREMENTS.md) — FR-01 through FR-12; NFR-01 through NFR-12; implementation evidence and limitations.
- [Detailed HLD](../Architechture%20Design/HLD.md) — System Architecture, High-Level DFD, Component and Deployment diagrams.
- [Detailed LLD](../Architechture%20Design/LLD.md) — Class, Sequence, State Machine and ER diagrams.

This historical document remains for existing design notes. Where historical planned scope differs from current code, the new requirement/status matrix and current source files distinguish implemented, partial and planned behaviors. No distributed infrastructure is deployed in the present single-PC design.
