# MechMotor 1.0 implementation roadmap

## Merged milestones
- Inventory foundation: CRUD, case-insensitive global search, structured filters, allowlisted sorting and bounded pagination.
- Backup and restore: verified SQLite snapshots, staged restore, schema validation and a pre-restore safety backup.
- Category hierarchy and supplier contact/code management.
- Vehicle fitment management and fitment-aware search.
- Saved inventory views, filtered CSV export and transactional create-only CSV import.
- Inventory dashboard metrics.
- Signed Ed25519 activation/license verification and UI feature gating.
- Single-item sales checkout and atomic supplier stock receiving.
- Windows CI on Java 21 with Maven clean verify.
- Searchable sales history with payment and refund audit details.
- Service-layer feature entitlement enforcement using `@RequiresFeature` and an AOP guard.

## Current milestone
- Purchase history, guarded receipt cancellation and atomic multi-item receiving (merged).
- Supplier payment tracking and payable balances (merged): partial payments, outstanding balance, payment audit/void, overpayment protection and receipt-cancellation safeguards.

- **Multi-item sales cart — merged:** up to 100 distinct items, overflow-safe totals, atomic sale snapshots, stock deductions, ledger movements and payment.
- **Formal purchase orders — implementation branch:** create and search orders before delivery, expected date/notes, reasoned cancellation, and atomic full receipt that creates a linked stock receipt. CI and merge pending; partial receiving and supplier returns/credit notes remain future work.

## Remaining production-readiness work
1. **Sales lifecycle:** merged full-sale voids, per-item partial returns/refunds, discount-aware cumulative rounding and atomic stock reversal/return audit. Remaining: end-to-end cashier workflow validation.
2. **Purchasing lifecycle:** multi-item receipts and supplier payment tracking are implemented. Remaining: partial receipt support, supplier returns/credit notes and end-to-end payable reconciliation validation.
3. **Invoices and reports:** merged printable PDF sales invoices generated from immutable snapshots. Operational CSV exports for sales, supplier receipts, stock movements and supplier payable reconciliation are implemented. Remaining: validate reconciliation against real shop records, shop profile/tax settings and invoice template review.
4. **Windows distribution:** validated JavaFX runtime layout, jpackage EXE installer, clean-machine install/launch/upgrade/uninstall testing, preserving user data.
5. **Release quality:** representative-catalog performance tests, accessibility and UI workflow tests, dependency/license/vulnerability scans, backup/restore drills and documented recovery procedures.
6. **Operational readiness:** support diagnostics, structured local logs, safe application shutdown, data retention/export policies and a signed release process.
7. **Email automation (optional scope):** Gmail desktop OAuth, protected token storage, report scheduling, idempotency keys and bounded retries.

## Acceptance gate
Do not call this production-ready until the latest main commit passes CI, end-to-end sales/purchasing/returns scenarios pass on Windows, installer upgrade/uninstall preserves the database, restore drills succeed, and security/dependency checks are reviewed.

## Known limits
This remains an offline-first single-PC desktop app. It is not a multi-user networked system or a substitute for an accounting/tax compliance review. Local activation verification can be bypassed by a privileged attacker who patches the executable. Search performance and recovery objectives must be measured on representative data and hardware.