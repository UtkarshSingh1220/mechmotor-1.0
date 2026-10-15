# MechMotor 1.0 implementation roadmap

## Baseline committed
- Java 21 + Spring Boot desktop process (no embedded HTTP server).
- JavaFX desktop shell and inventory search table.
- Local SQLite file under the user's application data directory.
- Parameterized case-insensitive search across core part fields and related category/supplier fields.
- Starter schema, indexes, Maven build, unit tests and Windows CI workflow.

## Milestone status

- **Category hierarchy management — merged:** recursive category paths, root/subcategory creation, rename and guarded deactivation; CI passed.
- **Supplier management (in progress on `feat/supplier-management`):** edit supplier name, phone and unique code while preserving stable supplier IDs; CI pending.

- **Category hierarchy management (in progress on feat/category-hierarchy-management):** recursive category path display, root/subcategory creation, rename and guarded soft-deactivation; requires CI and review before merge.

- **Backup/restore hardening (in progress on `feat/backup-restore-hardening`):** add SQLite `VACUUM INTO` backups, integrity/schema validation, pre-restore safety snapshots, and desktop actions. Must pass CI and be reviewed before merge.
1. **Inventory foundation — partially implemented:** part create/edit/deactivate, category/supplier selection and creation, global search, initial structured filters, allowlisted sorting, bounded pagination, stock-adjustment audit and repository regression tests are committed on `codex/inventory-foundation`. Still required: nested category editing, vehicle fitment, saved views, import/export, representative-catalog benchmarks, and a passing CI run for the latest commit.
2. **Business workflows:** purchase receipts, sales/cart, returns, payment records, immutable stock movement audit, atomic transactions and invoice snapshots.
3. **Dashboard and reports:** KPI drill-downs, charts with accessible table alternatives, PDF/CSV export, backup/restore and shop settings.
4. **Licensing:** canonical signed payload, Ed25519 verification, protected issuer utility kept outside this repository, masked activation UI, injected clock and expiry-boundary tests. Never commit a private signing key or real activation secret.
5. **Email automation:** Gmail API desktop OAuth, protected token storage, report scheduler, idempotency keys and bounded retries.
6. **Windows packaging:** validate a clean Windows build, assemble JavaFX native runtime dependencies, jpackage installer and data-preserving upgrade/uninstall.
7. **Release hardening:** dependency/license/vulnerability scan, representative catalog performance test, recovery drills and user documentation.

## Known limits
This remains a starter implementation, not a production-ready shop system. Vehicle fitment, nested category management, saved views, import/export, sales/purchases, dashboard analytics, activation signing and enforcement, Gmail integration, backups, PDF invoices and EXE installer are not implemented. Run and review the latest GitHub Actions verification before relying on it.
