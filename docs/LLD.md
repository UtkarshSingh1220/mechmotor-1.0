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
- CSV export streams all active rows matching the currently applied inventory filters in bounded 200-row pages; output is UTF-8 with a BOM for spreadsheet compatibility, RFC-style quote escaping and formula-injection mitigation.
- Saved inventory views persist the search, structured filters, stock status, price/quantity bounds and sorting; pagination is intentionally reset when a view is loaded.
- Vehicle fitments are stored per part with make/model, year range, optional engine/trim and notes. Free-text inventory search checks these fields and years within the fitment range through a correlated EXISTS query. Fitment data is added through additive schema creation; backup restore applies the current idempotent schema to a staged copy before validation.
- Create/edit/deactivate operations are exposed through the service layer. Create/edit operations are transactional; stock quantity changes produce `ADJUSTMENT` rows in `stock_movements`. Deactivation is a soft update and retains history.
- Category lookup uses a recursive CTE to display nested paths. Root/subcategory creation, category rename and guarded soft-deactivation use the existing categories parent_id and active fields; active child categories or active inventory references prevent deactivation. Supplier lookup/creation use the existing schema; no schema change was required for this milestone. Supplier creation normalizes whitespace and reuses an existing name case-insensitively to avoid accidental duplicates from repeated submissions. Supplier records can be viewed and edited for name, phone and unique supplier code without changing supplier IDs, preserving part references.

SQLite's built-in `lower()` is not complete Unicode case folding. If multilingual case folding becomes a requirement, use a tested normalization policy or a suitable FTS/ICU strategy. Leading-wildcard substring queries will not use ordinary B-tree indexes efficiently; benchmark and add FTS5 when warranted.

## Threading
Never run JDBC, report generation or Gmail operations on the JavaFX Application Thread. The desktop shell uses a single daemon database executor for bounded sequential work and JavaFX Tasks to return results to the UI thread. Future concurrent workloads must retain a bounded execution policy and stale-result handling.

## Activation key design (planned)
Menu: Help → License & Activation; shortcut: Settings → License. Mask key by default. Verify a signed canonical payload using Ed25519 and an embedded public key. Keep issuer private key in a separate offline tool. License is valid through 2028-01-31 in Asia/Kolkata; protected operations must be denied from 2028-02-01 00:00 Asia/Kolkata. Inject a Clock for deterministic tests. Expiry must not delete data or disable permitted read-only viewing/backup/export. This module is not implemented in the starter code.

## Build and test
- `mvn clean test`
- `mvn javafx:run`
- `mvn dependency:tree`

## Windows packaging
Package only after validating the JavaFX native libraries, launcher configuration and dependency layout. Use `jpackage --type exe` on Windows; test install, offline launch, upgrade and uninstall on a clean machine. Installer generation may require WiX tooling depending on JDK packaging support.
