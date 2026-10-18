package com.mechmotor.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseMigrationRunnerTest {
    @TempDir Path tempDir;

    @Test
    void freshDatabaseGetsBaselineAndVersionRecord() throws Exception {
        DatabaseMigrationRunner runner = new DatabaseMigrationRunner(dataSource(tempDir.resolve("fresh.db")));

        runner.run(new DefaultApplicationArguments(new String[0]));
        runner.run(new DefaultApplicationArguments(new String[0]));

        try (Connection connection = dataSource(tempDir.resolve("fresh.db")).getConnection();
             Statement statement = connection.createStatement();
             ResultSet version = statement.executeQuery("SELECT MAX(version) FROM schema_version")) {
            assertTrue(version.next());
            assertEquals(9, version.getInt(1));
            try (ResultSet sales = statement.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='sales'")) {
                assertTrue(sales.next());
                assertEquals(1, sales.getInt(1));
            }
            try (ResultSet taxColumn = statement.executeQuery(
                    "SELECT COUNT(*) FROM pragma_table_info('sales') WHERE name='tax_amount_paise'")) {
                assertTrue(taxColumn.next());
                assertEquals(1, taxColumn.getInt(1));
            }
        }
    }

    @Test
    void legacyDatabaseGetsAdditiveColumnsWithoutLosingInventory() throws Exception {
        Path file = tempDir.resolve("legacy.db");
        DataSource source = dataSource(file);
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE categories(id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)");
            statement.execute("CREATE TABLE suppliers(id INTEGER PRIMARY KEY, name TEXT NOT NULL)");
            statement.execute("""
                CREATE TABLE parts(
                    id INTEGER PRIMARY KEY,
                    sku TEXT NOT NULL UNIQUE,
                    part_number TEXT,
                    name TEXT NOT NULL,
                    brand TEXT,
                    company TEXT,
                    material TEXT,
                    color TEXT,
                    category_id INTEGER REFERENCES categories(id),
                    supplier_id INTEGER REFERENCES suppliers(id)
                )
                """);
            statement.execute("CREATE TABLE inventory(part_id INTEGER PRIMARY KEY, quantity_on_hand INTEGER NOT NULL DEFAULT 0)");
            statement.execute("CREATE TABLE stock_movements(id INTEGER PRIMARY KEY, part_id INTEGER NOT NULL, quantity INTEGER NOT NULL, reason TEXT)");
            statement.execute("INSERT INTO categories(id,name) VALUES (1,'Brakes')");
            statement.execute("INSERT INTO suppliers(id,name) VALUES (1,'Legacy Supplier')");
            statement.execute("INSERT INTO parts(id,sku,name,brand,material,color,category_id,supplier_id) VALUES (1,'LEGACY-001','Brake pad','Brand','Ceramic','Black',1,1)");
            statement.execute("INSERT INTO inventory(part_id,quantity_on_hand) VALUES (1,7)");
            statement.execute("INSERT INTO stock_movements(id,part_id,quantity,reason) VALUES (1,1,-2,'Legacy adjustment')");
        }

        DatabaseMigrationRunner runner = new DatabaseMigrationRunner(source);
        runner.run(new DefaultApplicationArguments(new String[0]));
        runner.run(new DefaultApplicationArguments(new String[0]));

        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            try (ResultSet part = statement.executeQuery("SELECT sku,name,category_id,supplier_id,updated_at,purchase_price_paise,selling_price_paise,active FROM parts WHERE id=1")) {
                assertTrue(part.next());
                assertEquals("LEGACY-001", part.getString("sku"));
                assertEquals("Brake pad", part.getString("name"));
                assertEquals(1, part.getInt("category_id"), "Legacy category relationship must survive the upgrade.");
                assertEquals(1, part.getInt("supplier_id"), "Legacy supplier relationship must survive the upgrade.");
                assertFalse(part.getString("updated_at").isBlank());
                assertEquals(0, part.getLong("purchase_price_paise"));
                assertEquals(0, part.getLong("selling_price_paise"));
                assertEquals(1, part.getInt("active"));
            }
            try (ResultSet taxColumn = statement.executeQuery(
                    "SELECT COUNT(*) FROM pragma_table_info('sales') WHERE name='tax_amount_paise'")) {
                assertTrue(taxColumn.next());
                assertEquals(1, taxColumn.getInt(1));
            }
            try (ResultSet stock = statement.executeQuery("SELECT quantity_on_hand,reserved_quantity,reorder_level FROM inventory WHERE part_id=1")) {
                assertTrue(stock.next());
                assertEquals(7, stock.getInt("quantity_on_hand"));
                assertEquals(0, stock.getInt("reserved_quantity"));
                assertEquals(0, stock.getInt("reorder_level"));
            }
            try (ResultSet movement = statement.executeQuery("SELECT movement_type,created_at FROM stock_movements WHERE id=1")) {
                assertTrue(movement.next());
                assertEquals("ADJUSTMENT", movement.getString("movement_type"));
                assertFalse(movement.getString("created_at").isBlank());
            }
            statement.execute("INSERT INTO stock_movements(part_id,movement_type,quantity,reference,reason) VALUES (1,'SUPPLIER_RETURN',-1,'SR-TEST','Supplier return migration test')");
            try (ResultSet supplierReturn = statement.executeQuery("SELECT movement_type,quantity FROM stock_movements WHERE reference='SR-TEST'")) {
                assertTrue(supplierReturn.next());
                assertEquals("SUPPLIER_RETURN", supplierReturn.getString("movement_type"));
                assertEquals(-1, supplierReturn.getInt("quantity"));
            }
            try (ResultSet category = statement.executeQuery("SELECT name FROM categories WHERE id=1")) {
                assertTrue(category.next());
                assertEquals("Brakes", category.getString(1));
            }
            try (ResultSet supplier = statement.executeQuery("SELECT name FROM suppliers WHERE id=1")) {
                assertTrue(supplier.next());
                assertEquals("Legacy Supplier", supplier.getString(1));
            }
            try (ResultSet foreignKeys = statement.executeQuery("PRAGMA foreign_key_check")) {
                assertFalse(foreignKeys.next(), "Upgrade must not leave broken foreign-key relationships.");
            }
            try (ResultSet version = statement.executeQuery("SELECT MAX(version) FROM schema_version")) {
                assertTrue(version.next());
                assertEquals(9, version.getInt(1));
            }
        }
    }

    @Test
    void incompatibleLegacyDatabaseFailsClearlyAndDoesNotMarkBaselineApplied() throws Exception {
        Path file = tempDir.resolve("incompatible.db");
        DataSource source = dataSource(file);
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE parts(id INTEGER PRIMARY KEY, name TEXT)");
            statement.execute("INSERT INTO parts(id,name) VALUES (1,'No SKU')");
        }

        DatabaseMigrationRunner runner = new DatabaseMigrationRunner(source);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> runner.run(new DefaultApplicationArguments(new String[0])));
        assertTrue(error.getMessage().contains("could not safely initialize or upgrade"));
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement();
             ResultSet version = statement.executeQuery("SELECT COUNT(*) FROM schema_version")) {
            assertTrue(version.next());
            assertEquals(0, version.getInt(1));
        }
    }

    @Test
    void failedVersionedMigrationRollsBackAndCanBeRetriedAfterRepair() throws Exception {
        Path file = tempDir.resolve("interrupted-upgrade.db");
        DataSource source = dataSource(file);
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE schema_version (
                    version INTEGER PRIMARY KEY,
                    applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
            statement.execute("INSERT INTO schema_version(version) VALUES (1)");
            statement.execute("""
                CREATE TABLE parts (
                    id INTEGER PRIMARY KEY,
                    sku TEXT NOT NULL,
                    name TEXT NOT NULL
                )
                """);
            statement.execute("INSERT INTO parts(id,sku,name) VALUES (1,'RECOVERY-001','Recovery fixture')");
            // V2 attempts to add this column. Its existing presence simulates a
            // partially applied/externally modified legacy schema that must fail closed.
            statement.execute("""
                CREATE TABLE purchase_items (
                    id INTEGER PRIMARY KEY,
                    previous_purchase_price_paise INTEGER
                )
                """);
        }

        DatabaseMigrationRunner runner = new DatabaseMigrationRunner(source);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> runner.run(new DefaultApplicationArguments(new String[0])));
        assertTrue(failure.getMessage().contains("Database migration V2 failed"));

        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            try (ResultSet version = statement.executeQuery("SELECT MAX(version) FROM schema_version")) {
                assertTrue(version.next());
                assertEquals(1, version.getInt(1), "A failed migration must not advance the schema version.");
            }
            try (ResultSet part = statement.executeQuery("SELECT sku,name FROM parts WHERE id=1")) {
                assertTrue(part.next(), "Pre-upgrade inventory must survive a failed migration.");
                assertEquals("RECOVERY-001", part.getString("sku"));
                assertEquals("Recovery fixture", part.getString("name"));
            }
            try (ResultSet cancellationTable = statement.executeQuery(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='purchase_cancellations'")) {
                assertTrue(cancellationTable.next());
                assertEquals(0, cancellationTable.getInt(1),
                        "A failed migration must not leave its later DDL statements applied.");
            }
        }
    }

    private DataSource dataSource(Path file) throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource source = new SQLiteDataSource(config);
        source.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
        return source;
    }
}
