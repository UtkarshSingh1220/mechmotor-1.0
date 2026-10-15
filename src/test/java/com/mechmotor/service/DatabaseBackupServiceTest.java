package com.mechmotor.service;

import com.mechmotor.config.DatabaseConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

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
            statement.execute("CREATE TABLE categories (id INTEGER PRIMARY KEY, name TEXT)");
            statement.execute("CREATE TABLE suppliers (id INTEGER PRIMARY KEY, name TEXT)");
            statement.execute("CREATE TABLE parts (id INTEGER PRIMARY KEY, sku TEXT)");
            statement.execute("CREATE TABLE inventory (part_id INTEGER PRIMARY KEY, quantity_on_hand INTEGER)");
            statement.execute("CREATE TABLE stock_movements (id INTEGER PRIMARY KEY, part_id INTEGER, quantity INTEGER)");
            statement.execute("INSERT INTO parts(id, sku) VALUES (1, 'BRAKE-001')");
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
            statement.executeUpdate("INSERT INTO parts(id, sku) VALUES (2, 'FILTER-002')");
        }

        Path safetyBackup = service.restoreFrom(backup);
        assertTrue(Files.isRegularFile(safetyBackup));
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
