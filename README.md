# MechMotor 1.0

**Offline-first vehicle spare-parts shop management for Windows**  
Java 21 · Spring Boot · JavaFX · Spring JDBC · SQLite · Maven

MechMotor is designed as a lightweight desktop application for a single shop PC. It aims to manage parts inventory, classification, suppliers, customers, purchasing, sales, stock movements, invoices, local reports, scheduled Gmail reports and activation licensing. Core shop workflows are intended to remain available without internet access; Gmail delivery requires internet access and valid Google OAuth authorization.

> **Project status:** Inventory CRUD and the first advanced-search milestone are implemented on the working branch, with automated repository tests added. This remains an in-progress starter, not a production-ready release: saved views, vehicle fitment, imports/exports, purchasing, sales, reporting, licensing, backups and packaging remain incomplete. CI verification for the current branch must pass before this milestone is considered validated.

## Activation and licensing

MechMotor validates signed Ed25519 activation tokens before loading inventory. The application contains only the public verification key; the signing private key must remain outside this repository and be protected offline.

- Default activation file: `%LOCALAPPDATA%\\MechMotor\\license\\activation.key` on Windows.
- Fallback when `LOCALAPPDATA` is unavailable: `%USERPROFILE%\\.mechmotor\\license\\activation.key`.
- Override for testing or managed deployments: JVM property `-Dmechmotor.licenseFile=/path/to/activation.key`.
- In the desktop header, select **Activation…** to view status, license ID, customer, expiry and features. Use **Reveal stored activation key** to view the installed token.
- To issue a customer key, compile/run `tools/license-issuer/IssueLicense.java` with the offline private key. Never put that private key in Git, the installer, or the application JAR.

See `docs/LLD.md` for token validation and issuer usage.

## CSV inventory import/export

Use the **CSV** menu to export the currently applied inventory filters or import a CSV catalog. Import requires the exported column set, supports quoted commas/quotes/newlines, and is create-only: duplicate SKUs are not updated. The complete import is validated and committed atomically; a bad row or existing SKU rolls back the batch. Imports are limited to 25 MB and 20,000 rows. Create categories and suppliers before importing references to them. Vehicle fitments are managed separately and are not included in the CSV.

## Sales checkout

Select an inventory row and choose **Sell selected**. Checkout supports quantity, unit price, discount, optional customer/payment reference, and CASH/UPI/CARD/BANK_TRANSFER. The sale header, item snapshot, payment, stock decrement and stock-ledger movement commit atomically. Insufficient stock or a failed write prevents the sale from being posted. The current version supports one item per sale and full payment only; voids/refunds and PDF invoices are not yet implemented.

## Sale voids and refund recording

Use **Void sale / refund…** to enter the original sale number, a required reason and an optional refund reference. The app restores sold stock, writes reversal movements and records the refund audit atomically. It prevents a sale from being voided twice. **It does not transfer money** through a bank, UPI app or card terminal; process the actual refund separately and record its reference.

## Purchase receiving

Select a part and choose **Receive stock**. Select the supplier, enter received quantity and unit cost, and optionally enter the supplier invoice/delivery-note reference. The app records a supplier receipt, snapshots item/supplier details, updates the latest purchase cost, increases inventory and writes a positive stock-ledger movement in one transaction. If any step fails, the complete receipt rolls back. Supplier payments, multi-item purchase orders and returns are not yet implemented.

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
| Dashboard | Clickable KPI cards, sales trends, recent sales/invoices, low-stock alerts, quick actions and drill-downs to filtered records | Planned |
| Parts & inventory | Create, view, update and deactivate parts; inventory quantities; SKU/barcode/part number; brand/company, material, color, category, costs and reorder level | Implemented: create/edit/deactivate forms, validation, category/supplier selection and stock-adjustment audit; vehicle fitment/dimensions remain planned |
| Global search | Case-insensitive query across configured part fields, ranked exact matches and useful empty states | Implemented for SKU, barcode, part number, name, brand/company, material, color, category and supplier with exact-match ranking and literal wildcard escaping |
| Advanced filters | Category/subcategory, brand/company, material, color, vehicle type/make/model/year, supplier, active status, low/out-of-stock, price and quantity ranges | Partially implemented: brand, material, color, category, supplier, stock status and quantity/price ranges; vehicle fitment, subcategory tree and saved views remain planned |
| Sorting and pagination | Sort by allowed columns, page controls, bounded result sizes and retained filter state | Implemented: allowlisted sort keys, 50-row pages, bounded page size and previous/next controls |
| Saved views | Store reusable filter presets such as low stock, a brand or a part category | Planned |
| Classification | Nested categories, vehicle types/models, manufacturers and part compatibility/fitment mapping | Category creation and category selection implemented; nested hierarchy management and vehicle fitment remain planned |
| Suppliers & purchasing | Supplier CRUD, purchase orders, goods receipt, purchase lines and supplier returns | Planned |
| Sales & customers | Customer CRUD, cart, discounts, taxes, checkout, payment records, invoice and returns workflow | Planned |
| Stock ledger | Auditable stock movements for purchases, sales, returns, adjustments and reversals | Schema foundation exists; transactional workflows planned |
| Reports | Daily sales, purchases, inventory valuation, low stock and stock movement reports; PDF/CSV export | Planned |
| Email reports | Recipient configuration, daily/weekly/monthly schedules, timezone, sections, format, test send and delivery history | Planned |
| License & activation | Enter signed key/file; show license status, product, license ID, licensee/shop, expiry and last validation; masked key with admin-only reveal/copy | Planned; expiry is not enforced by the starter |
| Settings & maintenance | Shop profile, backups, restore, data folder, log level and scheduler status | Planned |
| Windows installer | Installer containing a tested application/runtime layout; data preserved on upgrade/uninstall | Planned |
| CI and tests | Windows CI using Java 21 and Maven verification | Workflow committed; successful run must be confirmed in GitHub Actions |

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

The target packaging approach is JDK `jpackage`, built and tested on Windows. The installer should bundle the application runtime so users do not need a separate Java installation.

Planned release flow:

1. Run `mvn clean test package`.
2. Inspect the actual Maven output and assemble a distribution with the launcher, application JAR, dependency JARs, JavaFX/native libraries and resources as required by the selected launch layout.
3. Configure `jpackage --type exe` for that validated distribution.
4. Confirm the correct main class/launcher configuration. Do not blindly pass a Spring Boot executable/fat JAR to `jpackage` without validating its launch layout.
5. Resolve any required Windows installer tooling (WiX requirements depend on the JDK/packaging setup).
6. Test on a clean Windows machine: install, launch offline, search inventory, verify the data path, upgrade and uninstall without deleting user data.

An illustrative command shape—not yet a validated release command—is:

```powershell
mvn clean test package

# After creating and validating target\dist:
jpackage `
  --type exe `
  --name "MechMotor" `
  --app-version "1.0.0" `
  --input "target\dist" `
  --main-jar "mechmotor-launcher.jar" `
  --dest "target\installer" `
  --win-menu `
  --win-shortcut
```

The launcher JAR and input layout in this example must be adapted to the actual build output before use.

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
- Multi-item purchase orders, purchase cancellation/returns and supplier payment/accounting workflows.
- PDF invoices and reports, shop settings and report scheduling/email integration.
- Windows EXE installer and clean-machine install/upgrade/uninstall validation.
- Representative inventory performance benchmarks, dependency/security/license scanning, restore drills and end-to-end shop workflow tests.

This is not yet certified production-ready. Do not use it as the sole system of record until the release blockers and clean Windows validation are complete.
## Design documents

- [High-Level Design (HLD)](docs/HLD.md) — system architecture, modules, technology decisions, UX, non-functional requirements and release acceptance criteria.
- [Low-Level Design (LLD)](docs/LLD.md) — packages, data model, search/query rules, transaction behavior, license logic, testing and build/packaging steps.
- [Implementation roadmap](docs/IMPLEMENTATION-ROADMAP.md) — starter baseline, next milestones and known gaps.
- [Codex instructions](AGENTS.md) — engineering constraints and implementation workflow.

## Security

Never commit API keys, OAuth tokens, license issuer private keys, production activation material, real customer records or production database files. Use test credentials only in local environments and mock external integrations in CI.
