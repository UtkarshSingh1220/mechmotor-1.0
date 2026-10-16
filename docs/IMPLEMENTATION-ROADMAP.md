# MechMotor 1.0 implementation roadmap

## Baseline committed
- Java 21 + Spring Boot desktop process (no embedded HTTP server).
- JavaFX desktop shell and inventory search table.
- Local SQLite file under the user's application data directory.
- Parameterized case-insensitive search across core part fields and related category/supplier fields.
- Starter schema, indexes, Maven build, unit tests and Windows CI workflow.

## Milestone status

- **CSV inventory import (in progress on `feat/csv-inventory-import`):** validated create-only import with atomic rollback, price/stock preservation and desktop UI; CI pending.

- **Saved inventory views (in progress on `feat/saved-inventory-views`):** persist/load/delete filter presets with case-insensitive unique names; CI pending.

- **Vehicle fitment — merged:** per-part make/model/year compatibility records, fitment search, management UI and legacy-backup schema upgrade; CI passed.

- **Filtered CSV export — merged:** paged filtered exports, spreadsheet-safe escaping and formula-injection mitigation; CI passed.

- **Category hierarchy management — merged:** recursive category paths, root/subcategory creation, rename and guarded deactivation; CI passed.
- **Supplier management — merged:** supplier name, phone and unique code editing with stable IDs; CI passed for the code commit.

- **Backup/restore hardening — merged:** verified SQLite snapshots, integrity/schema validation, staged restore, pre-restore safety backup, desktop actions and regression tests; CI passed.
1. **Inventory foundation — partially implemented:** part create/edit/deactivate, category/supplier selection and creation, global search, initial structured filters, allowlisted sorting, bounded pagination, stock-adjustment audit and repository regression tests are committed on `codex/inventory-foundation`. Still required: representative-catalog benchmarks and release-level end-to-end validation.
2. **Business workflows:** purchase receipts, sales/cart, returns, payment records, immutable stock movement audit, atomic transactions and invoice snapshots.
3. **Dashboard and reports:** KPI drill-downs, charts with accessible table alternatives, PDF/CSV export, backup/restore and shop settings.
4. **Licensing:** canonical signed payload, Ed25519 verification, protected issuer utility kept outside this repository, masked activation UI, injected clock and expiry-boundary tests. Never commit a private signing key or real activation secret.
5. **Email automation:** Gmail API desktop OAuth, protected token storage, report scheduler, idempotency keys and bounded retries.
6. **Windows packaging:** validate a clean Windows build, assemble JavaFX native runtime dependencies, jpackage installer and data-preserving upgrade/uninstall.
7. **Release hardening:** dependency/license/vulnerability scan, representative catalog performance test, recovery drills and user documentation.

## Known limits
This remains a starter implementation, not a production-ready shop system. Vehicle fitment, nested category management, saved views, import/export, sales/purchases, dashboard analytics, activation signing and enforcement, Gmail integration, backups, PDF invoices and EXE installer are not implemented. Run and review the latest GitHub Actions verification before relying on it.
