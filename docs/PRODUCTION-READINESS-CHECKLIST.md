# Production-readiness acceptance gates

This checklist is the release gate for MechMotor 1.0. A feature being merged or unit tests passing is not, by itself, evidence that the Windows desktop product is production-ready.

## Gate 1 — Source and CI
- [ ] Latest main commit has a successful GitHub Actions run for the Java 21 / Maven clean verification workflow.
- [ ] mvn --batch-mode clean verify passes from a clean checkout.
- [ ] No skipped or disabled tests hide failures; document intentional exclusions.
- [ ] Review dependency and license scan results; resolve critical/high vulnerabilities or document an approved, time-bound exception.
- [ ] Review the diff and migration compatibility for every release-bound PR.
Evidence: commit SHA, CI run URL, test summary, scan output, reviewer and date.

## Gate 2 — Database migration and recovery
- [ ] Fresh install creates the current schema and records the expected schema version.
- [ ] A representative legacy database upgrades through every migration in order.
- [ ] Migration failure stops startup without opening a partially upgraded database.
- [ ] Backup creates a consistent SQLite snapshot while the application is open.
- [ ] Restore rejects malformed, incompatible, or incomplete backups without replacing the live database.
- [ ] Failed restore leaves the original database usable and preserves a pre-restore safety copy.
- [ ] Backup → modify data → restore recovers inventory, suppliers, fitments, purchases, sales, payment/refund audit, and stock movements.
- [ ] Recovery instructions identify the data directory and explain how to preserve a copy before reinstalling.
Evidence: sanitized test database versions, backup/restore logs, recovered record counts, recovery-time measurements.

## Gate 3 — Core inventory and catalog scale
- [ ] Case-insensitive search and structured filters work together without dropping matches.
- [ ] Search special characters (% _ !), Unicode input, blank input, and long input are covered.
- [ ] Sort keys are allowlisted and pagination is bounded.
- [ ] Nested category rename/deactivation and supplier edits preserve historical references.
- [ ] Vehicle fitment matching covers make/model, year range, optional engine and trim; duplicate compatibility rows are rejected.
- [ ] Saved views round-trip all supported filters and sorting.
- [ ] CSV export respects active filters and safely escapes values; import is create-only, bounded, and all-or-nothing.
- [ ] Benchmark with at least 10k, 50k, and 100k representative catalog rows. Record machine, dataset shape, warm/cold state, median and p95 latency for global search, filtered search, count query, and page navigation.
- [ ] No UI action blocks the JavaFX Application Thread during database, file, or PDF work.
Evidence: dataset-generation instructions, benchmark results, JavaFX workflow test results. Do not set performance thresholds until baseline measurements exist and target hardware is agreed.

## Gate 4 — Sales, returns, purchasing and payment integrity
- [ ] Multi-line sale posts sale header, immutable item snapshots, payment, stock deductions, and ledger movements atomically.
- [ ] Insufficient stock, integer overflow, or an injected write failure rolls back the complete sale.
- [ ] Full void and partial returns are guarded against duplicate posting and produce correct stock-ledger and refund audit entries.
- [ ] Discount allocation and cumulative integer rounding ensure all returned units refund exactly the original net sale amount.
- [ ] Multi-line supplier receipt posts receipt, item snapshots, cost snapshots, stock changes, and movements atomically.
- [ ] Receipt cancellation is blocked when stock cannot be reversed safely or supplier payment history exists.
- [ ] Partial/full supplier payments reject overpayment and duplicate active references; voiding a payment record never claims to reverse the external transfer.
- [ ] Supplier returns/credit notes and formal purchase-order lifecycle are either implemented and tested or clearly marked unsupported in the UI and documentation.
- [ ] End-to-end tests verify inventory, balances, receipt/sale history, and ledger totals after success, cancellation, return, and injected failure.
Evidence: scenario matrix, expected-versus-actual totals, database assertions for each transaction boundary.

## Gate 5 — Reports and documents
- [ ] Sales invoice PDFs use immutable transaction snapshots and do not silently overwrite existing files.
- [ ] PDF output is checked for long names, empty optional fields, page overflow, and non-ASCII data.
- [ ] Operational sales/purchase/stock reports reconcile to source transactions and state date range and currency.
- [ ] Shop identity, tax settings, invoice numbering, and report templates receive business-owner review before statutory accounting use.
- [ ] File writes use a temporary output and publish only after generation succeeds.

## Gate 6 — Activation and security
- [ ] Only the Ed25519 public verification key is packaged; the signing private key remains offline and outside the repository.
- [ ] Valid, invalid, malformed, expired, future-issued, tampered, and unknown-feature tokens are tested.
- [ ] Protected service mutations are denied when the license is invalid/expired, not merely hidden in the UI.
- [ ] Expiry does not delete, corrupt, or prevent export/recovery of user data.
- [ ] No API keys, OAuth tokens, real shop data, or signing secrets appear in source, build artifacts, logs, or fixtures.
- [ ] Review local-file permissions, CSV formula injection, SQL parameterization, path handling, and sensitive diagnostic output.

## Gate 7 — Windows packaging and clean-machine acceptance
- [ ] Build a versioned Windows EXE installer from a clean CI artifact using the supported JavaFX runtime layout.
- [ ] Install and launch on a clean supported Windows machine with no development JDK installed.
- [ ] Verify offline startup, activation dialog, data-directory creation, and first-run database initialization.
- [ ] Upgrade from the previous release while preserving the user-data directory and existing database.
- [ ] Uninstall and reinstall; confirm documented data-retention behavior and that user data is not silently deleted.
- [ ] Verify paths containing spaces and non-ASCII characters, Windows Defender/SmartScreen behavior, and desktop/start-menu shortcuts.
- [ ] Test shutdown during idle and an active database operation; confirm no database corruption.
- [ ] Verify backup/restore after install, upgrade, and uninstall/reinstall.
- [ ] Record installer hash, version, OS build, test operator, results, and known issues.

## Gate 8 — Release operations
- [ ] Structured local logs include timestamps and error context without secrets or unnecessary personal data.
- [ ] Support diagnostics explain how to collect logs and database/schema version safely.
- [ ] Document backup frequency, restore procedure, data export, retention, and incident escalation.
- [ ] Define versioning, release notes, artifact checksum/signing, rollback strategy, and release approval.
- [ ] If Gmail reports ship, validate desktop OAuth consent, protected token storage, bounded retries, idempotency, and offline behavior; otherwise keep this feature explicitly out of scope.

## Release decision
A release is **not production-ready** until Gates 1, 2, 4, 6, and 7 pass, relevant Gate 3 and Gate 5 checks pass, and evidence is reviewed. Any exception must identify the risk, owner, mitigation, and expiry date.

This checklist does not assert that any gate has passed. Check items only after recording reproducible evidence.
