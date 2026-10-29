# MechMotor 1.0

**Offline-first vehicle spare-parts shop management for Windows**  
Java 21 · Spring Boot · JavaFX · Spring JDBC · SQLite · Maven

MechMotor is designed as a lightweight desktop application for a single shop PC. It aims to manage parts inventory, classification, suppliers, customers, purchasing, sales, stock movements, invoices, local reports, scheduled Gmail reports and activation licensing. Core shop workflows are intended to remain available without internet access; Gmail delivery requires internet access and valid Google OAuth authorization.

> **Project status (2026-10-18):** Implemented workflows include inventory CRUD/search/filters, category hierarchy and vehicle fitment, saved views, CSV import/export, inventory metrics, signed Ed25519 activation and service-level feature gating, multi-item sales with discount/tax snapshots, voids/partial returns, purchase orders with partial receiving, supplier payments/returns, PDF invoices, operational CSV reports, and database backup/restore. PR #53 added a safeguard that rejects supplier returns which would create an untracked supplier credit; supplier-credit carry-forward/settlement is not implemented. The Windows workflow builds and smoke-tests EXE/MSI installers on hosted runners, but physical Windows acceptance, representative-shop reconciliation, restore drills and performance/release review remain outstanding. **Do not use this as the sole live-shop system until the release gate in [issue #51](https://github.com/UtkarshSingh1220/mechmotor-1.0/issues/51) is closed.**

## Architecture design diagrams and requirements

Full **functional requirements** (FR-01 through FR-12), **non-functional requirements** (NFR-01 through NFR-12), and the eight requested diagram types (nine diagrams total) are maintained under [Architechture Design](Architechture%20Design/README.md):

| Level | Diagram type | Image | Editable design |
|---|---|---|---|
| HLD | System Architecture | [SVG](Architechture%20Design/diagrams/HLD-01-system-architecture.svg) | [HLD](Architechture%20Design/HLD.md) |
| HLD | High-Level DFD | [SVG](Architechture%20Design/diagrams/HLD-02-high-level-dfd.svg) | [HLD](Architechture%20Design/HLD.md) |
| HLD | Component Diagram | [SVG](Architechture%20Design/diagrams/HLD-03-component-diagram.svg) | [HLD](Architechture%20Design/HLD.md) |
| HLD | Deployment Diagram | [SVG](Architechture%20Design/diagrams/HLD-04-deployment-diagram.svg) | [HLD](Architechture%20Design/HLD.md) |
| LLD | Class Diagram | [SVG](Architechture%20Design/diagrams/LLD-01-class-diagram.svg) | [LLD](Architechture%20Design/LLD.md) |
| LLD | Purchase Sequence | [SVG](Architechture%20Design/diagrams/LLD-02-purchasing-sequence.svg) | [LLD](Architechture%20Design/LLD.md) |
| LLD | Gmail Report Sequence | [SVG](Architechture%20Design/diagrams/LLD-03-gmail-report-sequence.svg) | [LLD](Architechture%20Design/LLD.md) |
| LLD | License State Machine | [SVG](Architechture%20Design/diagrams/LLD-04-license-state-machine.svg) | [LLD](Architechture%20Design/LLD.md) |
| LLD | ER Diagram | [SVG](Architechture%20Design/diagrams/LLD-05-entity-relationship.svg) | [LLD](Architechture%20Design/LLD.md) |

[View requirement/status matrices](Architechture%20Design/REQUIREMENTS.md) · [Architecture documentation index](Architechture%20Design/README.md)

![Current high-level MechMotor system architecture](Architechture%20Design/diagrams/HLD-01-system-architecture.svg)

**Deployment scope:** Offline-first Windows desktop, in-process JavaFX + Spring Boot services, Spring JDBC + SQLite, local reports/backups, optional Google OAuth/Gmail. No internal HTTP/gRPC service, Kubernetes, message queue, CDN or load balancer is currently used.

**Functional requirements at a glance** (full evidence and implementation status: [requirements matrix](Architechture%20Design/REQUIREMENTS.md)):

| IDs | Core capabilities | Status |
|---|---|---|
| FR-01–03 | Signed activation, inventory CRUD/search, categories/fitment/suppliers | Implemented |
| FR-04–06 | Purchase orders/receiving, sales/returns, stock movement audit | Implemented with stated financial restrictions |
| FR-07–08 | Date-range reports, CSV/PDF, Gmail OAuth and recipient selection | Implemented; live Gmail authorization required |
| FR-09–10 | Local user directory, shop profile, backup/restore | Implemented; directory roles do not enforce login |
| FR-11 | Desktop navigation and consistent functional dialogs | Settings has five working tabs; other page-level navigation needs review |
| FR-12 | Unattended scheduled Gmail reports / delivery history | Planned, not implemented |

**Non-functional requirements at a glance** ([NFR-01–12 evidence and diagram mappings](Architechture%20Design/REQUIREMENTS.md)):

| IDs | Quality attribute / design control | Acceptance status |
|---|---|---|
| NFR-01–03 | Offline-first, atomic stock/accounting, signed licensing | Implemented patterns; field validation required |
| NFR-04–06 | Scoped OAuth, recoverable SQLite, JavaFX responsiveness | Implemented; OAuth/restore drills pending |
| NFR-07–09 | Paged search, signed-install readiness, Java/Windows testing | Performance benchmarking and certificate-based release signing pending |
| NFR-10–11 | Local logging/migrations; single-PC deployment, no server infrastructure | Implemented |
| NFR-12 | True role-based authorization for directory users | Gap; directory roles only |


## Activation and licensing

MechMotor validates signed Ed25519 activation tokens before loading inventory. The application contains only the public verification key; the signing private key must remain outside this repository and be protected offline.

- Default activation file: `%LOCALAPPDATA%\\MechMotor\\license\\activation.key` on Windows.
- Fallback when `LOCALAPPDATA` is unavailable: `%USERPROFILE%\\.mechmotor\\license\\activation.key`.
- Override for testing or managed deployments: JVM property `-Dmechmotor.licenseFile=/path/to/activation.key`.
- A missing or expired license opens the activation dialog before the main workspace. The dashboard does not expose the activation token; the Settings license information displays expiry only.
- To issue a customer key, compile/run `tools/license-issuer/IssueLicense.java` with the offline private key. Never put that private key in Git, the installer, or the application JAR.

See `docs/LLD.md` for token validation and issuer usage.

## CSV inventory import/export

Use the **CSV** menu to export the currently applied inventory filters or import a CSV catalog. Import requires the exported column set, supports quoted commas/quotes/newlines, and is create-only: duplicate SKUs are not updated. The complete import is validated and committed atomically; a bad row or existing SKU rolls back the batch. Imports are limited to 25 MB and 20,000 rows. Create categories and suppliers before importing references to them. Vehicle fitments are managed separately and are not included in the CSV.

## Sales checkout

Select an inventory row and choose **Sell selected**. Checkout supports quantity, unit price, discount, optional customer/payment reference, and CASH/UPI/CARD/BANK_TRANSFER. The sale header, item snapshot, payment, stock decrement and stock-ledger movement commit atomically. Insufficient stock or a failed write prevents the sale from being posted. Checkout supports up to 100 distinct cart lines and full payment only. Sale voids restore stock and record a refund audit; actual payment refunds must be processed separately. Partial returns/refunds are supported; Sales invoice PDFs are supported; operational CSV reports are available for sales, supplier receipts, stock movements and supplier payable reconciliation.

## Sale voids and refund recording

Use **Void sale / refund…** to enter the original sale number, a required reason and an optional refund reference. The app restores sold stock, writes reversal movements and records the refund audit atomically. It prevents a sale from being voided twice. **It does not transfer money** through a bank, UPI app or card terminal; process the actual refund separately and record its reference.

## Sales invoice PDFs

Use **Sales invoice PDF…** to enter the original sale number and save a printable PDF. It is generated from the saved sale/item snapshots, payment records, and return/void audit entries, so later inventory edits do not change the original receipt. Existing files are never silently overwritten. Amounts are printed in INR; actual refund settlement must be verified separately.

## Sales returns and refunds

Use **Returns / refunds** and enter the original sale number. Select the item line, enter the quantity, refund method, and optional transaction reference/reason. MechMotor records the return, restores stock, and appends a `RETURN` ledger movement in one transaction. It allocates sale-level discounts proportionally across cart lines and uses cumulative integer rounding so returning every unit refunds exactly the original net sale total. Refunds are recorded for manual settlement; no money is sent automatically to a bank, UPI app or card terminal.

## Purchase receiving

Select a part and choose **Receive stock**. Select the supplier, enter received quantity and unit cost, and optionally enter the supplier invoice/delivery-note reference. The app records a supplier receipt, snapshots item/supplier details, updates the latest purchase cost, increases inventory and writes a positive stock-ledger movement in one transaction. If any step fails, the complete receipt rolls back. Purchase history, guarded stock-receipt cancellation, multi-item stock receiving, supplier payment/account-payable tracking and a date-bounded supplier payable reconciliation CSV are supported. Formal purchase orders can be created before delivery, searched, and cancelled with a reason before any stock is received. Orders support partial or full goods receipts across multiple deliveries: each receipt records the supplier reference, received quantities, linked stock receipt and ledger movements atomically; the order remains partially received until every ordered quantity is posted. Over-receipts are rejected, and an order with received stock cannot be cancelled. Creating an order does not change stock. The report counts only posted supplier payments; voided payments are excluded. Payment entries are bookkeeping records only; they do not transfer money. Supplier returns and supplier credit notes are recorded against original receipt lines, cannot exceed the unreturned quantity or unreserved stock, reduce inventory and outstanding payable atomically, and appear in payable reconciliation. A return is rejected before writes if its value exceeds the current outstanding payable; the application does not yet carry forward or settle supplier-credit balances. Posted returns are immutable; accounting/tax treatment and actual supplier settlement still require review.

## Database upgrades

On first startup, MechMotor now bootstraps a versioned schema and records the applied version in `schema_version`. Legacy inventory databases receive additive compatibility columns before the current schema is validated. Later schema changes belong in ordered `src/main/resources/db/migration/V<number>__description.sql` files; do not rely on `CREATE TABLE IF NOT EXISTS` to add columns to existing tables. If a migration fails, startup stops rather than continuing against an incomplete schema. Make an independent backup before installing a release that changes database behavior.

## Backup and restore

The inventory workspace includes local database backup and restore actions. Backups are created as verified SQLite snapshots in a folder you choose. Restore validates SQLite integrity and required MechMotor tables, stages the candidate, and creates a separate pre-restore backup before replacing the live database. Keep backups on a separate drive or secure storage; test restore procedures before relying on them. Close other MechMotor instances before restoring. This feature is not a substitute for off-device backups or release recovery drills.

## Contents

- [Product goals](#product-goals)
- [Technology and architecture](#technology-and-architecture)
- [Feature and functionality specification](#feature-and-functionality-specification)
- [Inventory search specification](#inventory-search-specification)
- [Data model](#data-model)
- [Business rules and transaction integrity](#business-rules-and-transaction-integrity)
- [Activation key and licensing](#activation-key-and-licensing)
- [Reports and scheduled Gmail delivery](#reports-and-scheduled-gmail-delivery)
- [UI and interaction requirements](#ui-and-interaction-requirements)
- [Security, privacy and offline behavior](#security-privacy-and-offline-behavior)
- [Performance and reliability targets](#performance-and-reliability-targets)
- [Repository layout](#repository-layout)
- [Requirements and local development](#requirements-and-local-development)
- [Testing and acceptance criteria](#testing-and-acceptance-criteria)
- [Windows EXE packaging](#windows-exe-packaging)
- [Implementation status and roadmap](#implementation-status-and-roadmap)
- [Known limitations](#known-limitations)
- [Design documents](#design-documents)

## Product goals

MechMotor is intended to provide:

- Parts and inventory CRUD with searchable product attributes.
- Category and subcategory management, vehicle models and part-fitment mapping.
- Supplier and customer records.
- Purchase orders, goods receipt and supplier returns.
- Sales checkout, discounts, taxes, payments, invoices and customer returns.
- Stock movement history and low-stock/out-of-stock visibility.
- An interactive dashboard with KPI cards, trends, recent transactions and quick actions.
- PDF/CSV reports, backup/restore and configurable scheduled email reports.
- A signed activation-key workflow with expiry enforcement that preserves shop data.
- A compact desktop distribution that bundles its Java runtime so the user does not need to install Java separately.

The initial target is one Windows PC with a local SQLite database. SQLite is not intended to be shared as a live database file across a network drive.

## Technology and architecture

| Layer | Design choice | Purpose |
|---|---|---|
| Desktop UI | JavaFX controls, FXML/CSS | Native desktop screens, tables, forms and interactions |
| Application lifecycle | Spring Boot application context | Dependency injection, configuration and service lifecycle |
| Web server | None by default | Keep the application desktop-only; do not add an embedded HTTP listener for the initial release |
| Business logic | Spring-managed services | Validation, business rules and transaction boundaries |
| Persistence | Spring JDBC/JDBC | Prepared SQL and transaction-aware database access |
| Local database | SQLite | Single-PC offline-first storage |
| PDF reports | Apache PDFBox | Local invoice/report generation |
| CSV reports | Apache Commons CSV | CSV export |
| Email | Gmail API + OAuth 2.0 | Scheduled report delivery; requires connectivity and authorization |
| Licensing | Signed offline license payload; Ed25519 planned | Local signature validation with public key in the client |
| Packaging | JDK `jpackage` | Windows installer with bundled runtime |
| Testing | JUnit 5 and Spring Boot test dependencies | Unit and integration tests |
| CI | GitHub Actions on Windows | Java 21 build and verification workflow |

### Architecture flow

```text
JavaFX Desktop UI (controls / FXML / CSS)
                |
                v
Controllers and UI tasks
                |
                v
Spring-managed application services
  |       |         |         |
  |       |         |         +-- Reports / Gmail jobs
  |       |         +------------ License guard
  |       +---------------------- Inventory / purchase / sales
  +------------------------------ Part search / classification
                |
                v
Spring JDBC repositories
                |
                v
SQLite + schema initialization / indexes

Infrastructure: PDF/CSV, OAuth, logs, backup and packaging
```

Spring Boot is embedded in the desktop process, but the application is configured with `WebApplicationType.NONE`. Do not add `spring-boot-starter-web` or a server listener unless a future requirement explicitly changes this architecture. Prefer JDBC over an ORM for the initial compact single-PC application.

## Feature and functionality specification

The table below describes the **intended product scope** from the HLD/LLD. It is a specification, not a claim that every module is implemented.

| Module | Intended functionality | Current status |
|---|---|---|
| Dashboard | Inventory KPIs, sales trends, recent transactions, low-stock alerts and quick actions | Inventory KPI metrics implemented; sales trends, recent sales dashboard and richer drill-downs remain planned |
| Parts & inventory | CRUD, stock adjustments, attributes, categories and suppliers | Implemented: create/edit/deactivate, validation, stock-adjustment audit and searchable inventory |
| Global search | Case-insensitive query across configured fields, exact-match ranking and useful empty states | Implemented for SKU, barcode, part number, name, brand/company, material, color, category and supplier; fitment-aware search is also implemented |
| Advanced filters | Category, brand, material, color, vehicle fitment, supplier, stock state, price and quantity | Implemented for current inventory filters, including vehicle fitment; search performance benchmarking and broader catalog QA remain |
| Sorting and pagination | Safe sorting and bounded pages | Implemented with allowlisted sort keys and bounded pagination |
| Saved views | Reusable filter presets | Implemented for search, filters and sorting |
| Classification | Nested categories and vehicle compatibility | Category hierarchy and vehicle fitment workflows implemented |
| Suppliers & purchasing | Supplier records, purchase orders, partial receiving, payments, returns and reconciliation | Implemented for current workflows; returns exceeding outstanding payable are rejected because supplier-credit carry-forward/settlement is not modeled |
| Sales & customers | Cart, discounts/tax, checkout, payments, invoices, voids and returns | Sales cart, transaction snapshots, payment records, voids/partial returns and PDF invoices implemented; external refund settlement remains manual |
| Stock ledger | Auditable movements for receipts, sales, returns, adjustments and reversals | Implemented for supported stock-affecting workflows with transactional updates |
| Reports | Operational CSV/PDF reports and reconciliation | Operational CSV exports and sales invoice PDFs implemented; report totals still require representative shop-data reconciliation |
| Email reports | Scheduled report delivery and delivery history | Optional scope; not release-blocking for core offline workflows, but not yet complete |
| License & activation | Signed activation, expiry/features and local key management | Signed Ed25519 validation, expiry checks and service-layer feature gating implemented; issuing customer keys requires the offline private key |
| Settings & maintenance | Shop settings, backup and restore | Verified SQLite backup/restore workflow implemented; restore drills and shop-profile/tax-setting acceptance remain |
| Windows installer | Bundled runtime, install/upgrade/uninstall and data preservation | Hosted Windows workflow builds EXE/MSI and runs install/launch/uninstall smoke checks; physical-machine acceptance remains open |
| CI and tests | Java 21 verification, security analysis and Windows packaging | PR #53 head passed Java CI, CodeQL and Windows packaging; post-merge main verification and release-gate evidence must be checked separately |

### Intended primary navigation

- Dashboard
- Inventory
- Categories
- Purchases
- Sales
- Customers
- Suppliers
- Reports
- Email Reports
- Settings
- License

The global top bar is intended to include global search, quick-create actions, database/backup status, online/offline status and a user/role menu.

## Inventory search specification

Search is a core product requirement. Users should be able to find a part by any configured searchable criterion and combine free-text search with structured filters.

### Searchable attributes

- SKU
- Barcode
- Part number and supplier part number
- Part name
- Brand, company and manufacturer
- Material and color
- Category and subcategory/category path
- Vehicle type, make, model and model year
- Supplier

The current starter SQL searches core part fields plus category and supplier names. Vehicle-fitment and supplier-part-number search need to be completed as those data relationships and UI workflows are implemented.

### Query behavior

1. Trim leading/trailing whitespace and normalize repeated spaces.
2. Apply a consistent case-normalization policy.
3. Treat user-entered search text as data; bind values as prepared-statement parameters.
4. Rank exact SKU/barcode matches first, then exact part number, then prefix/general text matches as the search implementation evolves.
5. Escape SQL wildcard characters when they are meant literally.
6. Keep result sets bounded and introduce page-based retrieval for larger catalogs.
7. Debounce typing by approximately 250 ms in the finished UI; cancel or ignore stale search results.
8. Show a clear no-results state, active filter chips and a one-click clear-filters action.

### Filter combination rules

- Multiple selected values inside one multi-select filter group are **ORed**.
- Different active filter groups are **ANDed** with the global query.
- Empty filters must not restrict results.
- Validate page index, page size and minimum/maximum ranges before querying.
- Sorting must use a hardcoded allowlist of sort keys. Never interpolate an arbitrary user-supplied SQL column name.

### Search performance notes

Indexes are planned/defined for frequently searched or sorted columns. A regular B-tree index generally cannot efficiently satisfy a leading-wildcard search such as `LIKE '%term%'`. Benchmark representative catalogs with `EXPLAIN QUERY PLAN`; add SQLite FTS5 or a normalized search table if substring search becomes a bottleneck.

SQLite's built-in `NOCASE` and `lower()` do not provide full Unicode case folding. The starter normalizes query case with Java's `Locale.ROOT`, but full multilingual case-insensitive matching requires a consistent tested normalization policy or an appropriate FTS/ICU strategy.

## Data model

The intended database entities and their primary responsibilities are:

| Table/entity | Main purpose and representative fields |
|---|---|
| `parts` | SKU, barcode, part number, name, brand/company, material, color, category/supplier references, purchase/selling price, active status and update time |
| `categories` | Category name, parent category and active status |
| `vehicle_models` | Vehicle type, manufacturer, model name and model year |
| `part_compatibility` | Many-to-many mapping between parts and compatible vehicle models |
| `suppliers` | Supplier name, phone and supplier code |
| `inventory` | Quantity on hand, reserved quantity and reorder level per part |
| `stock_movements` | Movement type, quantity, reference, reason and timestamp |
| `report_schedules` | Frequency, local time, timezone, recipients, format, report sections and enabled status |
| `license_record` | License ID, signed payload, activation timestamp and last-seen timestamp; never the issuer private key |

The current `schema.sql` includes the initial categories, suppliers, parts, inventory and stock-movement tables plus indexes and constraints. The full model and any new relationships need to be implemented through controlled schema changes/migrations.

### Database location

By default, the SQLite database is stored outside the installation directory under the user's application data area:

- Windows: `%LOCALAPPDATA%\\MechMotor\\data\\mechmotor.db`
- Development/test override: Java system property `mechmotor.dataDir`

Keeping user data separate from the application installation is required to support data-preserving upgrades and uninstall behavior.

## Business rules and transaction integrity

The following are design requirements for purchase, sales and inventory workflows:

- Validate input and part status before entering a transaction wherever safe.
- Use atomic transactions for transaction headers, line snapshots, payments, stock updates and stock-movement entries.
- Do not allow on-hand stock to become negative.
- Available quantity must account for reserved stock.
- For a stock deduction, use a conditional update that checks sufficient available quantity and require exactly one row to be updated. A zero-row update means the operation cannot proceed.
- If any line fails, roll back the entire checkout/purchase posting operation.
- Store money as integer minor units (paise) or use rigorously controlled decimal arithmetic; never use `double` for currency.
- Do not physically delete posted invoices or stock movements. Correct posted transactions using returns/reversals and retain an audit trail.
- Never call Gmail, render PDFs or wait for user interaction while holding a database write transaction.
- After a sale commits, invoice-rendering failure must not create another sale. Offer regeneration/reprint instead.
- Never blindly retry a checkout after a database-busy error.

## Activation key and licensing

### Where users will find it

- Primary menu: **Help → License & Activation**
- Shortcut: **Settings → License**

The screen is intended to show status, product, license ID, shop/licensee, expiry and last validation result. The activation key must be masked by default. Revealing or copying it requires administrator authorization, and the key must never be written to logs.

### License format and verification

The intended signed license payload includes product ID, license ID, issue time, not-before time, expiry and feature claims. The client verifies the exact canonical signed payload using a standard signature algorithm such as Ed25519.

- Embed only the **public verification key** in the client.
- Keep the issuer's private signing key outside the application repository and packaged client, preferably offline and secured.
- Accept a signed license string/file and persist signed claims in a protected application-data folder.
- Inject a `Clock` so expiry boundaries can be tested deterministically.
- Check the license at startup and inside protected service methods. Hiding UI buttons is not sufficient enforcement.

### Expiry requirement

The specification says the license is valid through **31 January 2028 in Asia/Kolkata**. Protected mutations must be denied from **1 February 2028 at 00:00 Asia/Kolkata**.

When expired, the application should block protected business mutations while allowing read-only viewing and backup/export where policy permits. The application must preserve the database, reports, invoices, backups and OAuth credentials.

A protected last-seen timestamp can detect obvious system-clock rollback, but offline enforcement is best-effort only: a privileged local attacker can tamper with a purely offline application. Do not claim perfect tamper resistance.

**Status:** The activation screen, signed-license verifier, issuer utility, service-layer guard and expiry enforcement are not implemented in the current starter.

## Reports and scheduled Gmail delivery

### Local reports

Planned reports include daily sales, purchases, inventory valuation, low-stock lists and stock-movement history. PDF output is intended to use PDFBox; CSV output is intended to use Commons CSV. Local report generation should work offline.

### Scheduled email reports

The report scheduler is intended to support:

- Daily, weekly and monthly schedules.
- Shop timezone and local send time.
- Multiple recipients, selected report sections and PDF/CSV format.
- Test-send and send-now actions.
- Delivery history, attempts, status, errors and Gmail message ID.
- Windows Task Scheduler report-runner mode for delivery while the desktop UI is closed.
- A unique execution key for schedule ID + report period + report type to prevent duplicate sends after restarts.
- Bounded exponential backoff for transient failures.
- Persisted pending jobs when offline, without blocking inventory or sales.

Gmail delivery requires an internet connection, Gmail API configuration, Desktop OAuth client and valid authorization. Tokens must be protected and excluded from source control. The app should request only the required send scope. The PC must be powered on and internet-connected for unattended delivery.

**Status:** Gmail OAuth, schedule UI, report generation, runner mode, retry handling and delivery history are planned, not implemented.

## UI and interaction requirements

The finished desktop UI is intended to provide:

- A compact, consistent sidebar and global top bar.
- A global search field and inventory toolbar with Advanced Filters, Save View, Import/Export and Add Part.
- A filter drawer with multi-select chips, price/quantity ranges, active status, low/out-of-stock toggles and Reset/Clear All.
- A virtualized table with sortable columns, row selection, keyboard navigation, context menu and page controls.
- Part detail/edit tabs for Overview, Classification/Fitment, Stock, Supplier and Movement History.
- Clickable dashboard KPI cards that navigate to the relevant filtered records.
- Charts with table/accessible alternatives and locally aggregated data.
- Inline form validation, sensible defaults, autocomplete for categories/brands/models, destructive-action confirmations and unsaved-change warnings.
- Non-blocking notifications, progress indicators, actionable errors, safe retry options and correlation IDs where useful.
- Accessible labels, keyboard shortcuts, logical focus order and sufficient contrast.

### Threading rules

Database, file, PDF and network operations must not block the JavaFX Application Thread. Use bounded background tasks/executors, disable duplicate submissions while work is in progress and update UI state on the JavaFX thread. Cancellation should only be offered where it is safe.

## Service-layer license enforcement

The application checks signed license features at the Spring service boundary as well as in the desktop UI. Inventory operations require `INVENTORY`; CSV import/export requires `CSV`; sales, returns and invoice generation require `SALES`; supplier receiving requires `PURCHASING`. An inactive, expired, invalid or insufficient key blocks the proxied service call.

## Security, privacy and offline behavior

- Core CRUD, inventory, sales/purchases and local PDF/CSV work are intended to operate without internet.
- Gmail delivery is the principal planned online dependency.
- Use prepared SQL statements, database constraints and validation.
- Do not log activation keys, OAuth secrets or other credentials.
- Do not commit real customer data, production databases, OAuth tokens or private signing keys.
- Store user data outside the install directory and test backup/restore before entering real shop data.
- Expired licensing must not delete shop data.
- SQLite is intended for a single local PC, not a live shared database on a network drive.
- Use dependency, license and vulnerability checks before release.

## Performance and reliability targets

These are targets to measure, not currently verified benchmarks:

| Area | Requirement |
|---|---|
| Common local searches | Target 100–200 ms on reference hardware, measured with representative data |
| Memory | Bounded executors, paginated results and no unbounded caches |
| Database reliability | Atomic transactions, constraints, migrations and tested backup/restore |
| Offline mode | Core shop operations and local reports without internet |
| Search safety | Prepared statements, allowlisted sorting, correct filter semantics and tested pagination |
| Scheduler reliability | Restart recovery, missed-run handling, bounded retries and duplicate prevention |
| Packaging | Clean Windows installation without preinstalled Java; data survives upgrade/uninstall |
| Diagnostics | Use Java Flight Recorder to diagnose CPU, allocation and GC issues when needed |

## Repository layout

```text
mechmotor-1.0/
├── pom.xml
├── AGENTS.md
├── README.md
├── docs/
│   ├── HLD.md
│   ├── LLD.md
│   └── IMPLEMENTATION-ROADMAP.md
├── src/
│   ├── main/
│   │   ├── java/com/mechmotor/
│   │   │   ├── MechMotorApplication.java
│   │   │   ├── config/
│   │   │   ├── domain/
│   │   │   ├── repository/
│   │   │   └── service/
│   │   └── resources/
│   │       ├── application.properties
│   │       ├── schema.sql
│   │       └── styles/app.css
│   └── test/java/com/mechmotor/
└── .github/workflows/ci.yml
```

The package tree will grow as controllers, repositories, services, migrations, license, report, backup, email and scheduler modules are implemented.

## Requirements and local development

### Prerequisites

- JDK 21
- Maven 3.9+
- Git
- IntelliJ IDEA Community Edition or another Java IDE (optional)
- Windows for validating the target EXE installer

Check the installed tools:

```bash
java -version
mvn -version
```

Make sure Maven is using JDK 21.

### Run tests

```bash
mvn clean test
```

### Run the JavaFX application

```bash
mvn javafx:run
```

### Inspect dependencies

```bash
mvn dependency:tree
```

The first build may need internet access to download Maven dependencies. Normal inventory use is designed to be offline after the application is installed.

## Testing and acceptance criteria

The HLD/LLD call for the following test coverage as modules are implemented:

| Test area | Required cases |
|---|---|
| Search unit tests | Case variants, whitespace, empty query, multiple criteria, AND/OR filter semantics and sort allowlist |
| Search integration | SQLite indexes, pagination, FTS consistency and representative query plans |
| CRUD | Duplicate SKU/barcode, invalid prices, category cycles and deactivation of referenced records |
| Inventory | Receipt, sale, return, adjustment, insufficient stock and transaction rollback |
| License | Valid key, exact expiry boundary, tampered payload, wrong product, not-before, clock rollback policy and data preservation |
| Reports | Date/timezone boundaries, empty periods, totals and large CSV streaming |
| Gmail | Send success, rate limit, timeout, revoked credentials and no live credentials in CI |
| Scheduler | Restart recovery, missed run, duplicate execution key and disabled schedule |
| UI | Loading states, keyboard interaction, filter chips, clear filters, error states and no UI-thread blocking |
| Packaging | Clean Windows install, offline launch, correct data path and data preservation through upgrade/uninstall |

### Release acceptance criteria

A release should not be called complete until:

- Search works across all declared searchable fields and combined filters behave correctly.
- Dashboard actions navigate to the correct filtered views.
- Failed checkout rolls back all writes and cannot oversell stock.
- Offline shop operations work except for online Gmail delivery.
- License expiry is enforced at the exact specified instant and all shop data remains intact.
- The Windows installer works on a clean machine without a preinstalled Java runtime.
- Performance, memory, dependency/license and vulnerability checks are documented.
- Automated tests pass and backup/restore has been verified.

The committed CI workflow runs `mvn --batch-mode --no-transfer-progress clean verify` on a Windows runner with Java 21. Check the repository's Actions tab for the actual result; a committed workflow does not itself prove that a build has passed.

## Windows EXE packaging

The Windows packaging workflow builds a Java 21 runtime containing the required JavaFX modules, creates a Windows app-image, and produces versioned EXE and MSI installers with the bundled runtime. The workflow verifies the launcher configuration and classpath, smoke-tests the app-image, installs and launches both MSI and EXE packages on a hosted Windows runner, validates database migration/data retention checks, and publishes SHA-256 checksums with the workflow artifact.

The application version is resolved from `pom.xml`; package filenames and `jpackage --app-version` use that same version. A stable Windows upgrade UUID is used for EXE/MSI packages so later releases can participate in a consistent upgrade path.

For every change merged to `main` that affects the application or packaging, the Windows workflow runs automatically. Test installers must come from a successful workflow whose head SHA matches the current `main` commit. Approved customer installers should be published as assets on a versioned GitHub Release rather than referenced through a historical temporary Actions artifact.

Hosted CI smoke tests are necessary but are not the final production acceptance gate. Before release, validate the installer on a physical supported Windows machine/account, including offline launch, Windows Defender/SmartScreen behavior, upgrade from a populated prior version, uninstall/reinstall, activation/data preservation, and backup/restore. Track that evidence in issue #51 and the production-readiness checklist.

## Implementation status and roadmap

### Implemented and merged
- [x] Java 21, Spring Boot desktop context, JavaFX UI and local SQLite storage.
- [x] Inventory CRUD, case-insensitive search, filters, safe sorting and bounded pagination.
- [x] Category hierarchy, supplier management, vehicle fitment, saved views and dashboard metrics.
- [x] Filtered CSV export and create-only transactional CSV import.
- [x] Signed Ed25519 activation keys with local verification and feature gates.
- [x] Single-item sale checkout and single-item supplier stock receiving, each with atomic stock-ledger updates.
- [x] Verified local database backup/restore and pre-restore safety snapshots.
- [x] Windows GitHub Actions CI using Java 21 and Maven verify.

### Current milestone
- Multi-item sales cart: in development on feat/multi-item-sales-cart. Checkout must post all line snapshots, stock deductions, stock movements and payment in one transaction.

### Remaining release blockers
- Sale voids/refunds and return workflows with explicit reversal records.
- Formal purchase orders and partial receiving are implemented; supplier returns/credit notes are in review pending CI and Windows workflow acceptance. End-to-end accounting reconciliation remains a release gate.
- PDF invoices and reports, shop settings and report scheduling/email integration.
- Windows EXE/MSI generation and MSI install/launch/uninstall checks run in CI on a fresh hosted Windows runner; EXE installer behavior, upgrade paths, and real user-database preservation still require acceptance testing.
- Representative inventory performance benchmarks, dependency/security/license scanning, restore drills and end-to-end shop workflow tests.

This is not yet certified production-ready. Do not use it as the sole system of record until the release blockers and clean Windows validation are complete.
## Windows packaging\n\nThe Windows packaging workflow builds a Java 21 app-image, runs packaged and installed launch smoke tests, produces EXE/MSI installers, and validates MSI install/uninstall on a fresh hosted Windows runner. Download artifacts from a successful [Windows desktop package workflow](https://github.com/UtkarshSingh1220/mechmotor-1.0/actions/workflows/windows-package.yml). CI validation does not yet cover the EXE installer, upgrades from an older release, or preservation of a real shop database across upgrade/uninstall; those remain release gates.\n\n## Design documents

- [High-Level Design (HLD)](docs/HLD.md) — system architecture, modules, technology decisions, UX, non-functional requirements and release acceptance criteria.
- [Low-Level Design (LLD)](docs/LLD.md) — packages, data model, search/query rules, transaction behavior, license logic, testing and build/packaging steps.
- [Implementation roadmap](docs/IMPLEMENTATION-ROADMAP.md) — starter baseline, next milestones and known gaps.
- [Codex instructions](AGENTS.md) — engineering constraints and implementation workflow.

## Security

Never commit API keys, OAuth tokens, license issuer private keys, production activation material, real customer records or production database files. Use test credentials only in local environments and mock external integrations in CI.

## Operational reports

Choose **Operational reports…** in the inventory workspace to export a date-bounded CSV for sales, supplier receipts, or stock-ledger movements. Dates are inclusive, the maximum range is 366 days, exports run off the JavaFX Application Thread, and existing destination files are not overwritten. Report exports require the CSV license feature. These exports are operational records, not tax/accounting-certified statements; recorded payment amounts do not confirm external settlement.


## User directory and Gmail report delivery (experimental branch)

The desktop **Settings → Manage Users** screen supports creating, editing and deactivating
user directory records (name, email, role label). Deactivation is a soft delete and retains
the row for audit/history. These directory roles **do not implement login or enforce permissions**:
the app remains a single-PC desktop application. No user passwords are collected or stored.

**Settings → Connect Gmail / Set Default Email** configures the default report recipient
in the local SQLite `app_preferences` table. To authorize outbound Gmail from MechMotor:

1. In Google Cloud Console, configure the OAuth consent screen with the Gmail send
   permission `https://www.googleapis.com/auth/gmail.send`. Add allowed test users if the
   app is still in testing.
2. Create an OAuth client of type **Desktop app** (not Web Application).
3. Set the `MECHMOTOR_GOOGLE_CLIENT_ID` environment variable for the MechMotor process
   to that client's public client ID. Do not store OAuth client secrets in the repository.
4. Restart the desktop application, open Settings, and click **Connect Google Account**.
   Sign in and authorize in the browser. Authorization uses loopback redirect, random state,
   and PKCE. Access tokens are held only in memory and are not saved to disk; connect
   again after restarting or when the access token expires. An internet connection is
   required. The configured account is the sender; the default email is a recipient.

The **Reports → Generate Report** button writes an operational CSV file to a chosen location
without overwriting an existing file. **Send Mail** generates a new CSV and attaches it to
a Gmail API message. The recipient textbox and checkbox behave as follows:

| Recipient field | Send to both checked | Email destination |
|---|---|---|
| Empty | Either | Configured default recipient |
| Filled | No | Entered recipient only |
| Filled | Yes | Entered recipient plus default (deduplicated) |

When a required default is missing, the application blocks sending with a validation error.
The app confirms recipients before sending, runs export/network operations outside the
JavaFX thread, and deletes the temporary attachment after the attempt. Gmail sends can
fail due to connectivity, consent-screen, API configuration, account policy or quota. Gmail
authorization is **not** an application login or an RBAC system. There is no automatic
email scheduler, durable outbound queue, send retry or multi-user security model yet.

This branch requires Windows UI testing, Google OAuth consent testing and Maven test/packaging
verification before being merged or advertised as production ready.


## Architecture Design — HLD, LLD, functional and non-functional requirements

The repository contains a dedicated, source-aligned **[Architechture Design](Architechture%20Design/README.md)** documentation folder. Its diagram source is Mermaid and renders directly in GitHub Markdown. The existing [high-level](docs/HLD.md) and [low-level](docs/LLD.md) design notes remain available.

| Document / diagram type | Requirements covered | Open document |
|---|---|---|
| **Functional requirements** FR-01–FR-12 | Inventory, category/fitment, purchasing, sales/refunds, licensing, reporting/Gmail, user directory, settings, backup, UI; status of planned scheduling | [Functional requirements table](Architechture%20Design/REQUIREMENTS.md#functional-requirements) |
| **Non-functional requirements** NFR-01–NFR-12 | Offline operation, security/privacy, transaction integrity, recovery, responsiveness, search, packaging, tests, operability, topology and RBAC limitations | [Non-functional requirements table](Architechture%20Design/REQUIREMENTS.md#non-functional-requirements) |
| **HLD: System Architecture Diagram** | UI → Spring services → SQLite, licensing, reporting, optional Gmail integration | [HLD-01](Architechture%20Design/HLD.md#hld-01--system-architecture-diagram) |
| **HLD: High-Level Data Flow Diagram (DFD)** | Inputs, validations, transactional data, local exports and email outputs | [HLD-02](Architechture%20Design/HLD.md#hld-02--high-level-data-flow-diagram-dfd) |
| **HLD: Component Diagram** | Code-level module boundaries, in-process dependencies, storage | [HLD-03](Architechture%20Design/HLD.md#hld-03--component-diagram) |
| **HLD: Deployment Diagram** | Windows installer, jlink/jpackage, local data paths and optional Google network boundary | [HLD-04](Architechture%20Design/HLD.md#hld-04--deployment--infrastructure-diagram) |
| **LLD: Class Diagram** | JavaFX composition, services, repository, feature enforcement | [LLD-01](Architechture%20Design/LLD.md#lld-01--class-diagram) |
| **LLD: Sequence Diagrams** | Atomic purchase receiving and Google OAuth/report sending workflows | [LLD-02 and LLD-03](Architechture%20Design/LLD.md) |
| **LLD: State Machine Diagram** | First-run activation, expiry, invalid key, license feature denial | [LLD-04](Architechture%20Design/LLD.md#lld-04--license-state-machine-diagram) |
| **LLD: ER Diagram** | SQLite parts/inventory/ledger, purchases, sales, users and preferences | [LLD-05](Architechture%20Design/LLD.md#lld-05--entity-relationship-diagram-current-schema-selected-relationships) |

**Actual technology/deployment constraints:** MechMotor is an offline-first JavaFX **desktop app** with embedded Spring Boot (no local HTTP server), Spring JDBC and SQLite on one PC. Its modules call each other in-process; REST/gRPC microservices, queues, CDNs, load balancers and Kubernetes are **not currently implemented**. The Gmail API is an external HTTPS integration requiring OAuth. The local `app_users` directory does **not** currently enforce sign-in roles. Scheduled unattended email remains a planned feature; on-demand report email exists. Diagram IDs link back to FR/NFR IDs for maintenance.

**Design maintenance:** Update [REQUIREMENTS.md](Architechture%20Design/REQUIREMENTS.md), [HLD.md](Architechture%20Design/HLD.md) and [LLD.md](Architechture%20Design/LLD.md) whenever source architecture, schemas, external interfaces, licensing or workflow behavior changes; validate against tests before claiming a feature implemented.
