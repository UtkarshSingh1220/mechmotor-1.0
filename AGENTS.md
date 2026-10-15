# Codex instructions — MechMotor 1.0

## Product goal
Build an offline-first Windows desktop vehicle spare-parts shop application using Java 21, Spring Boot (no embedded HTTP server), JavaFX, Spring JDBC and SQLite. Treat `docs/HLD.md`, `docs/LLD.md` and `docs/IMPLEMENTATION-ROADMAP.md` as the current project contract.

## Engineering rules
- Prefer small, maintainable, production-oriented changes over speculative abstractions.
- Keep the application desktop-only; do not add `spring-boot-starter-web` or microservices.
- Keep blocking JDBC, file, PDF and network operations off the JavaFX Application Thread.
- Bind SQL values with prepared statements. Never interpolate user input or unvalidated sort identifiers into SQL.
- Use integer paise for money, never floating-point currency.
- Preserve transaction atomicity for sales, purchases, stock movements and payment records. Never allow overselling.
- Add schema migrations for schema changes and integration tests for repository/database behavior.
- Keep user data under the application-data directory, not under the installation directory.
- Do not commit API keys, OAuth tokens, private license signing keys, real shop data or secrets.
- Use Ed25519 signed licenses with only the public verification key in the app. Keep license issuance separate and offline. Do not invent or commit a real activation key.
- License policy: valid through 2028-01-31 in Asia/Kolkata; deny protected mutations from 2028-02-01T00:00:00+05:30. Expiry must not delete shop data.
- SQLite NOCASE/lower() is not full Unicode case folding. Document the normalization policy and add tests for the supported behavior.
- Avoid unbounded result sets and thread pools; add pagination and benchmark representative catalog sizes.
- Never describe the product as production-ready until tests, security review, backup/restore, and a clean Windows installer test pass.

## Workflow
1. Inspect the current implementation and the design documents before changing architecture.
2. Implement one roadmap milestone at a time.
3. Add or update automated tests for every behavior change.
4. Run `mvn --batch-mode clean verify` and report failures honestly.
5. Keep README and roadmap aligned with implemented functionality; label incomplete features clearly.
6. Before release, validate the packaged app on a clean Windows machine, including offline startup, data preservation on upgrade/uninstall, and EXE installation.

## Current implementation status
The repository currently contains a starter UI, a core global inventory search, a local SQLite schema, basic unit tests and a Windows CI workflow. CRUD workflows, full advanced filters, sales/purchasing transactions, dashboard analytics, activation enforcement, Gmail reports, PDF invoices, backup/restore and the EXE installer are not yet implemented.
