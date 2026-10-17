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
            assertEquals(6, version.getInt(1));
            try (ResultSet sales = statement.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='sales'")) {
                assertTrue(sales.next());
                assertEquals(1, sales.getInt(1));
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
                    color TEXT
                )
                """);
            statement.execute("CREATE TABLE inventory(part_id INTEGER PRIMARY KEY, quantity_on_hand INTEGER NOT NULL DEFAULT 0)");
            statement.execute("CREATE TABLE stock_movements(id INTEGER PRIMARY KEY, part_id INTEGER NOT NULL, quantity INTEGER NOT NULL, reason TEXT)");
            statement.execute("INSERT INTO categories(id,name) VALUES (1,'Brakes')");
            statement.execute("INSERT INTO suppliers(id,name) VALUES (1,'Legacy Supplier')");
            statement.execute("INSERT INTO parts(id,sku,name,brand,material,color) VALUES (1,'LEGACY-001','Brake pad','Brand','Ceramic','Black')");
            statement.execute("INSERT INTO inventory(part_id,quantity_on_hand) VALUES (1,7)");
            statement.execute("INSERT INTO stock_movements(id,part_id,quantity,reason) VALUES (1,1,-2,'Legacy adjustment')");
        }

        DatabaseMigrationRunner runner = new DatabaseMigrationRunner(source);
        runner.run(new DefaultApplicationArguments(new String[0]));
        runner.run(new DefaultApplicationArguments(new String[0]));

        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            try (ResultSet part = statement.executeQuery("SELECT sku,name,updated_at,purchase_price_paise,selling_price_paise,active FROM parts WHERE id=1")) {
                assertTrue(part.next());
                assertEquals("LEGACY-001", part.getString("sku"));
                assertEquals("Brake pad", part.getString("name"));
                assertFalse(part.getString("updated_at").isBlank());
                assertEquals(0, part.getLong("purchase_price_paise"));
                assertEquals(0, part.getLong("selling_price_paise"));
                assertEquals(1, part.getInt("active"));
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
            try (ResultSet version = statement.executeQuery("SELECT MAX(version) FROM schema_version")) {
                assertTrue(version.next());
                assertEquals(6, version.getInt(1));
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

    private DataSource dataSource(Path file) throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource source = new SQLiteDataSource(config);
        source.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
        return source;
    }
}
