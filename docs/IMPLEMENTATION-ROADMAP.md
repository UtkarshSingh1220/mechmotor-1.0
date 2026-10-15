# MechMotor 1.0 implementation roadmap

## Baseline committed
- Java 21 + Spring Boot desktop process (no embedded HTTP server).
- JavaFX desktop shell and inventory search table.
- Local SQLite file under the user's application data directory.
- Parameterized case-insensitive search across core part fields and related category/supplier fields.
- Starter schema, indexes, Maven build, unit tests and Windows CI workflow.

## Next milestones
1. **Inventory foundation:** CRUD forms, category hierarchy, suppliers, vehicle fitment, full filter DTO, saved views, pagination and query-plan benchmarks.
2. **Business workflows:** purchase receipts, sales/cart, returns, adjustments, payment records, immutable stock movement audit, atomic transactions and invoice snapshots.
3. **Dashboard and reports:** KPI drill-downs, charts with accessible table alternatives, PDF/CSV export, backup/restore and shop settings.
4. **Licensing:** canonical signed payload, Ed25519 verification, protected issuer utility kept outside this repository, masked activation UI, injected clock and expiry-boundary tests. Never commit a private signing key or real activation secret.
5. **Email automation:** Gmail API desktop OAuth, protected token storage, report scheduler, idempotency keys and bounded retries.
6. **Windows packaging:** validate a clean Windows build, assemble JavaFX native runtime dependencies, jpackage installer and data-preserving upgrade/uninstall.
7. **Release hardening:** dependency/license/vulnerability scan, representative catalog performance test, recovery drills and user documentation.

## Known limits of this initial commit
This is a starter implementation, not a production-ready shop system. Sales/purchases, full filter drawer, dashboard analytics, activation signing and enforcement, Gmail integration, backups, PDF invoices and EXE installer are not implemented yet. Verify build and tests in GitHub Actions before relying on it.
