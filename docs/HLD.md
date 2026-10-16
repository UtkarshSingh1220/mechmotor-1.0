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
The inventory workspace trims and normalizes whitespace and performs case-insensitive matching across SKU, barcode, part number, name, brand/company, material, color, category and supplier. Exact SKU/barcode/part-number matches rank first. The initial advanced filters include brand, material, color, category, supplier, stock status, quantity range and selling-price range. Filter groups are combined with AND; free-text matches across searchable fields use OR. Results are paged and sort keys are allowlisted. Vehicle fitment and saved views are implemented; performance benchmarking remains planned. User values are always bound parameters.

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


## Formal purchase orders

Formal purchase orders are stored separately from goods receipts. Creating an order snapshots the supplier and requested item quantities/costs and leaves on-hand inventory unchanged. Orders can be searched and cancelled with a required reason while still `ORDERED`. Receiving currently posts the entire order as one atomic goods receipt and links the receipt back to the order; partial receiving and supplier returns/credit notes remain future work.

## Sale voids and returns
Sale voids are audited transactions: sale status, stock restoration, reversal ledger entries and refund metadata commit together. Actual money movement remains external and must be reconciled by the shop. Partial returns are separately recorded against original sales and should not be confused with a full sale void.


Partial returns are tracked per sale item. Sale-level discounts are allocated proportionally across lines with deterministic paise rounding; full returns across all lines refund exactly the original net sale total. Return inventory, refund audit and stock-ledger entries commit atomically. Full-sale voids are blocked once any partial return exists.


Invoice PDFs are generated locally from immutable sale/item snapshots plus payment, return and void audit records. The PDF is a business record, not proof of external refund settlement.


## Service-level license enforcement
Signed feature entitlements are checked both in the UI and through a Spring AOP guard on business services. The UI layer is for user-friendly feedback; service interception is the enforcement boundary for inventory, CSV, sales/invoices and purchasing operations.
