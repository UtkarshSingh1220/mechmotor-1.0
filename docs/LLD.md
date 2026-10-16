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


## Build and test
- `mvn clean test`
- `mvn javafx:run`
- `mvn dependency:tree`

## Windows packaging
Package only after validating the JavaFX native libraries, launcher configuration and dependency layout. Use `jpackage --type exe` on Windows; test install, offline launch, upgrade and uninstall on a clean machine. Installer generation may require WiX tooling depending on JDK packaging support.
