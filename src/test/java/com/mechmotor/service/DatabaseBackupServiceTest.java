package com.mechmotor.service;

import com.mechmotor.config.DatabaseConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseBackupServiceTest {
    @TempDir Path tempDir;
    private String previousDataDir;
    private DataSource dataSource;
    private Path databaseFile;
    private DatabaseBackupService service;

    @BeforeEach
    void setUp() throws Exception {
        previousDataDir = System.getProperty("mechmotor.dataDir");
        System.setProperty("mechmotor.dataDir", tempDir.resolve("data").toString());
        databaseFile = DatabaseConfiguration.resolveDatabasePath();
        Files.createDirectories(databaseFile.getParent());
        SQLiteDataSource sqlite = new SQLiteDataSource();
        sqlite.setUrl("jdbc:sqlite:" + databaseFile);
        dataSource = sqlite;
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
            statement.execute("INSERT INTO parts(id, sku, name) VALUES (1, 'BRAKE-001', 'Brake pad')");
        }
        service = new DatabaseBackupService(dataSource);
    }

    @AfterEach
    void tearDown() {
        if (previousDataDir == null) System.clearProperty("mechmotor.dataDir");
        else System.setProperty("mechmotor.dataDir", previousDataDir);
    }

    @Test
    void backupIsIntegrityCheckedAndRestoreCreatesSafetyBackup() throws Exception {
        Path backup = service.backupToDirectory(tempDir.resolve("exports"));
        assertTrue(Files.isRegularFile(backup));

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM parts");
            statement.executeUpdate("INSERT INTO parts(id, sku, name) VALUES (2, 'FILTER-002', 'Oil filter')");
        }

        Path safetyBackup = service.restoreFrom(backup);
        assertTrue(Files.isRegularFile(safetyBackup));
        try (Connection connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + safetyBackup);
             Statement statement = connection.createStatement();
             var result = statement.executeQuery("SELECT sku FROM parts")) {
            assertTrue(result.next(), "The pre-restore safety snapshot must retain the current database contents.");
            assertEquals("FILTER-002", result.getString(1));
            assertFalse(result.next());
        }
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             var result = statement.executeQuery("SELECT sku FROM parts")) {
            assertTrue(result.next());
            assertEquals("BRAKE-001", result.getString(1));
            assertFalse(result.next());
        }
    }

    @Test
    void backupRestorePreservesInventoryRelationshipsAndStockLedger() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO categories(id,name) VALUES (1,'Brakes')");
            statement.execute("INSERT INTO suppliers(id,name,supplier_code) VALUES (1,'Legacy supplier','SUP-001')");
            statement.execute("UPDATE parts SET category_id=1,supplier_id=1 WHERE id=1");
            statement.execute("INSERT INTO inventory(part_id,quantity_on_hand,reserved_quantity,reorder_level) VALUES (1,12,2,3)");
            statement.execute("""
                INSERT INTO stock_movements(part_id,movement_type,quantity,reference,reason)
                VALUES (1,'ADJUSTMENT',12,'RESTORE-ACCEPTANCE','Initial counted stock')
                """);
        }

        Path backup = service.backupToDirectory(tempDir.resolve("relationship-export"));

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("UPDATE categories SET name='Changed category' WHERE id=1");
            statement.execute("UPDATE suppliers SET name='Changed supplier' WHERE id=1");
            statement.execute("UPDATE parts SET name='Changed part' WHERE id=1");
            statement.execute("UPDATE inventory SET quantity_on_hand=1,reserved_quantity=0 WHERE part_id=1");
            statement.execute("DELETE FROM stock_movements WHERE reference='RESTORE-ACCEPTANCE'");
        }

        Path safetyBackup = service.restoreFrom(backup);
        assertTrue(Files.isRegularFile(safetyBackup), "Restore must keep a pre-restore recovery snapshot.");

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            try (var result = statement.executeQuery("""
                    SELECT p.sku,p.name,p.category_id,p.supplier_id,c.name AS category_name,s.name AS supplier_name
                    FROM parts p
                    JOIN categories c ON c.id=p.category_id
                    JOIN suppliers s ON s.id=p.supplier_id
                    WHERE p.id=1
                    """)) {
                assertTrue(result.next());
                assertEquals("BRAKE-001", result.getString("sku"));
                assertEquals("Brake pad", result.getString("name"));
                assertEquals(1, result.getInt("category_id"));
                assertEquals(1, result.getInt("supplier_id"));
                assertEquals("Brakes", result.getString("category_name"));
                assertEquals("Legacy supplier", result.getString("supplier_name"));
            }
            try (var result = statement.executeQuery(
                    "SELECT quantity_on_hand,reserved_quantity,reorder_level FROM inventory WHERE part_id=1")) {
                assertTrue(result.next());
                assertEquals(12, result.getInt("quantity_on_hand"));
                assertEquals(2, result.getInt("reserved_quantity"));
                assertEquals(3, result.getInt("reorder_level"));
            }
            try (var result = statement.executeQuery(
                    "SELECT movement_type,quantity,reason FROM stock_movements WHERE reference='RESTORE-ACCEPTANCE'")) {
                assertTrue(result.next());
                assertEquals("ADJUSTMENT", result.getString("movement_type"));
                assertEquals(12, result.getInt("quantity"));
                assertEquals("Initial counted stock", result.getString("reason"));
                assertFalse(result.next());
            }
        }
    }

    @Test
    void restoreMigratesOlderBackupByAddingNewFitmentTable() throws Exception {
        Path backup = service.backupToDirectory(tempDir.resolve("legacy-export"));
        try (Connection connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + backup);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE part_fitments");
            statement.execute("DROP TABLE saved_views");
            statement.execute("DROP TABLE sale_returns");
            statement.execute("DROP TABLE sale_voids");
            statement.execute("DROP TABLE payments");
            statement.execute("DROP TABLE sale_items");
            statement.execute("DROP TABLE sales");
            statement.execute("DROP TABLE purchase_items");
            statement.execute("DROP TABLE purchases");
        }

        service.restoreFrom(backup);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('part_fitments','saved_views','sales','sale_items','payments','purchases','purchase_items','sale_voids','sale_returns')")) {
            assertTrue(result.next());
            assertEquals(9, result.getInt(1));
        }
    }

    @Test
    void structurallyIncompleteDatabaseIsRejected() throws Exception {
        Path incomplete = tempDir.resolve("incomplete.db");
        try (Connection connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + incomplete);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE categories (id INTEGER PRIMARY KEY, name TEXT)");
            statement.execute("CREATE TABLE suppliers (id INTEGER PRIMARY KEY, name TEXT)");
            statement.execute("CREATE TABLE parts (id INTEGER PRIMARY KEY, sku TEXT)");
            statement.execute("CREATE TABLE inventory (part_id INTEGER PRIMARY KEY, quantity_on_hand INTEGER)");
            statement.execute("CREATE TABLE stock_movements (id INTEGER PRIMARY KEY, part_id INTEGER, quantity INTEGER)");
        }
        assertThrows(IllegalArgumentException.class, () -> service.restoreFrom(incomplete));
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             var result = statement.executeQuery("SELECT sku FROM parts")) {
            assertTrue(result.next());
            assertEquals("BRAKE-001", result.getString(1));
        }
    }

    @Test
    void liveDatabaseCannotBeUsedAsItsOwnRestoreSource() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> service.restoreFrom(databaseFile));
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             var result = statement.executeQuery("SELECT sku FROM parts")) {
            assertTrue(result.next());
            assertEquals("BRAKE-001", result.getString(1));
            assertFalse(result.next());
        }
    }

    @Test
    void invalidBackupDoesNotModifyLiveDatabase() throws Exception {
        Path invalid = tempDir.resolve("not-a-backup.db");
        Files.writeString(invalid, "not a sqlite database");
        assertThrows(IllegalArgumentException.class, () -> service.restoreFrom(invalid));
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             var result = statement.executeQuery("SELECT sku FROM parts")) {
            assertTrue(result.next());
            assertEquals("BRAKE-001", result.getString(1));
        }
    }
}
