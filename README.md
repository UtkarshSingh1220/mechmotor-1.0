# MechMotor 1.0

Offline-first vehicle spare parts shop desktop application. The initial implementation uses Java 21, Spring Boot (without an embedded web server), JavaFX, Spring JDBC and SQLite.

## Current starter features
- JavaFX desktop shell with global inventory search and results table.
- Case-insensitive search across SKU, barcode, part number, name, brand/company, material, color, category and supplier.
- Exact SKU, barcode and part-number matches are ranked first.
- SQLite database stored under the user's application data directory.
- Starter schema, constraints, indexes, Maven test setup and Windows GitHub Actions CI.

## Requirements
- JDK 21
- Maven 3.9+
- Windows is the target packaging platform; development may be possible on other JavaFX-supported platforms.

## Run locally
```bash
mvn clean test
mvn javafx:run
```

On Windows, the database defaults to `%LOCALAPPDATA%\\MechMotor\\data\\mechmotor.db`. To override it for a development/test run:

```bash
mvn javafx:run -Dmechmotor.dataDir=C:\\temp\\mechmotor-data
```

## Build a Windows installer
A Windows EXE installer using `jpackage` is a planned release milestone. The JavaFX native runtime and launcher layout must be validated on Windows before publishing an installer. See [implementation roadmap](docs/IMPLEMENTATION-ROADMAP.md).

## Design documents
- [High-Level Design](docs/HLD.md)
- [Low-Level Design](docs/LLD.md)
- [Implementation roadmap and known gaps](docs/IMPLEMENTATION-ROADMAP.md)

## Important status
This repository is an implementation starter, **not yet production-ready**. Sales/purchases, complete CRUD and advanced filters, dashboard analytics, signed activation-key enforcement, Gmail reporting, PDF invoices, backup/restore and EXE packaging remain future milestones. The license expiry requirement is documented but not enforced by this starter. Run CI and validate the application on a clean Windows machine before using it with real shop data.

## Security
Do not commit activation secrets, license issuer private keys, OAuth credentials, real customer data or production database files. Offline license checks cannot provide perfect tamper resistance against a privileged local attacker.
