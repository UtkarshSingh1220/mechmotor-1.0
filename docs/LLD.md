# MechMotor 1.0 — Low-Level Design

## Package layout
- `com.mechmotor`: JavaFX entry point and Spring lifecycle.
- `com.mechmotor.config`: local SQLite datasource and configuration.
- `com.mechmotor.domain`: immutable domain records.
- `com.mechmotor.repository`: JDBC queries.
- `com.mechmotor.service`: business services.
- `src/main/resources/schema.sql`: schema and indexes.
- `src/test/java`: unit and integration tests.

## Local database
Default path: `%LOCALAPPDATA%/MechMotor/data/mechmotor.db` on Windows. Override with `-Dmechmotor.dataDir=<path>` for tests or managed deployments. Keep the database out of the installation directory so upgrades do not overwrite user data.

## Search implementation
1. Trim query and collapse repeated whitespace.
2. Normalize query to a consistent case policy.
3. Escape wildcard characters for literal substring matching.
4. Bind all values through JDBC parameters.
5. Search indexed and related fields using left joins.
6. Rank exact SKU, barcode and part-number matches first.
7. Cap initial results to avoid unbounded UI memory; implement page-based queries before large catalogs.

SQLite's built-in `lower()` is not complete Unicode case folding. If multilingual case folding becomes a requirement, use a tested normalization policy or a suitable FTS/ICU strategy. Leading-wildcard substring queries will not use ordinary B-tree indexes efficiently; benchmark and add FTS5 when warranted.

## Threading
Never run JDBC, report generation or Gmail operations on the JavaFX Application Thread. Use bounded executors/tasks, disable duplicate submissions while work is running, and marshal UI updates back to the FX thread.

## Activation key design (planned)
Menu: Help → License & Activation; shortcut: Settings → License. Mask key by default. Verify a signed canonical payload using Ed25519 and an embedded public key. Keep issuer private key in a separate offline tool. License is valid through 2028-01-31 in Asia/Kolkata; protected operations must be denied from 2028-02-01 00:00 Asia/Kolkata. Inject a Clock for deterministic tests. Expiry must not delete data or disable permitted read-only viewing/backup/export. This module is not implemented in the starter code.

## Build and test
- `mvn clean test`
- `mvn javafx:run`
- `mvn dependency:tree`

## Windows packaging
Package only after validating the JavaFX native libraries, launcher configuration and dependency layout. Use `jpackage --type exe` on Windows; test install, offline launch, upgrade and uninstall on a clean machine. Installer generation may require WiX tooling depending on JDK packaging support.
