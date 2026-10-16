# MechMotor 1.0 — High-Level Design

## Purpose
Offline-first Windows desktop software for a vehicle spare-parts shop. The initial target is one PC and one local SQLite database. Core inventory operations should not depend on internet access.

## Architecture
- **Presentation:** JavaFX controls and CSS.
- **Application:** Spring Boot application context configured with `WebApplicationType.NONE`; no HTTP listener.
- **Services:** business rules and transaction boundaries.
- **Persistence:** Spring JDBC / prepared SQL over SQLite.
- **Infrastructure:** local application-data directory, logs, migrations, reports and packaging.
- **Delivery:** Maven development workflow and a Windows installer built with `jpackage` after validating the runtime layout.

## Main modules
Dashboard; parts and inventory; categories and vehicle fitment; suppliers and purchasing; sales and customers; stock movement ledger; PDF/CSV reports; backup/restore; license and activation; scheduled Gmail reports; settings.

## Search contract
The inventory workspace trims and normalizes whitespace and performs case-insensitive matching across SKU, barcode, part number, name, brand/company, material, color, category and supplier. Exact SKU/barcode/part-number matches rank first. The initial advanced filters include brand, material, color, category, supplier, stock status, quantity range and selling-price range. Filter groups are combined with AND; free-text matches across searchable fields use OR. Results are paged and sort keys are allowlisted. Vehicle fitment, saved views and performance benchmarking remain planned. User values are always bound parameters.

## Integrity and security
- Store currency as integer paise, never floating point.
- Use database constraints and atomic transactions for stock-affecting workflows.
- Never permit stock below zero; preserve posted transactions and stock history through reversals.
- Keep license issuer private keys and OAuth secrets out of source control and logs.
- Verify signed activation tokens locally with an embedded Ed25519 public key; keep the private issuer key offline and outside the repository. The activation token is stored in the per-user application data directory.
- Offline license enforcement is best-effort against a privileged local attacker.
- Backups and restore must be tested before real business data is entered.

## Non-functional targets
Measure local search latency on representative hardware and data; initial target for common queries is 100–200 ms. Bound background work, paginate results, keep blocking work off the JavaFX thread, and test packaged startup on a clean Windows machine.
