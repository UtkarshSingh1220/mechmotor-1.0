package com.mechmotor.service;

import com.mechmotor.config.DatabaseConfiguration;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Local SQLite backup and restore. Restore validates the candidate and creates
 * a verified pre-restore backup before replacing the live database file.
 */
@Service
public class DatabaseBackupService {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Map<String, Set<String>> REQUIRED_COLUMNS = Map.of(
            "categories", Set.of("id", "name", "parent_id", "active"),
            "suppliers", Set.of("id", "name", "phone", "supplier_code"),
            "parts", Set.of("id", "sku", "barcode", "part_number", "name", "brand", "company",
                    "material", "color", "category_id", "supplier_id", "purchase_price_paise",
                    "selling_price_paise", "active", "updated_at"),
            "inventory", Set.of("part_id", "quantity_on_hand", "reserved_quantity", "reorder_level"),
            "stock_movements", Set.of("id", "part_id", "movement_type", "quantity", "reference", "reason", "created_at"));

    private final DataSource dataSource;
    private final Path databaseFile;

    public DatabaseBackupService(DataSource dataSource) {
        this.dataSource = dataSource;
        this.databaseFile = DatabaseConfiguration.resolveDatabasePath();
    }

    /** Create a uniquely named, integrity-checked snapshot in the selected directory. */
    public Path backupToDirectory(Path directory) throws Exception {
        if (!Files.isRegularFile(databaseFile)) {
            throw new IOException("The local database does not exist yet.");
        }
        Path targetDirectory = directory.toAbsolutePath().normalize();
        Files.createDirectories(targetDirectory);
        Path destination = uniqueBackupPath(targetDirectory, "mechmotor-backup-" + FILE_TIME.format(LocalDateTime.now()));
        Path temporary = Files.createTempFile(targetDirectory, ".mechmotor-backup-", ".tmp");
        Files.deleteIfExists(temporary);
        try {
            checkpoint();
            vacuumInto(temporary);
            verifyDatabase(temporary);
            moveNewFile(temporary, destination);
            return destination;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Restore a validated backup. The current database is never replaced until
     * the candidate passes integrity/schema checks and a pre-restore backup succeeds.
     * The caller must serialize this operation with all other database work.
     */
    public Path restoreFrom(Path backupFile) throws Exception {
        Path source = backupFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new IOException("Choose an existing MechMotor backup file.");
        }
        if (source.equals(databaseFile)) {
            throw new IllegalArgumentException("The live database cannot be used as its own restore source.");
        }
        try {
            verifyDatabase(source);
        } catch (Exception ex) {
            throw new IllegalArgumentException("The selected file is not a valid, intact MechMotor database backup.", ex);
        }

        Files.createDirectories(databaseFile.getParent());
        Path staged = Files.createTempFile(databaseFile.getParent(), ".mechmotor-restore-", ".db");
        try {
            Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING);
            verifyDatabase(staged);

            // Do not risk replacing the only copy of the user's current data.
            Path safetyBackup = backupToDirectory(databaseFile.getParent().resolve("backups").resolve("pre-restore"));
            checkpoint();
            replaceFile(staged, databaseFile);
            return safetyBackup;
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private void checkpoint() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }
    }

    private static void vacuumInto(Path output) throws Exception {
        String escaped = output.toAbsolutePath().normalize().toString().replace("'", "''");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + DatabaseConfiguration.resolveDatabasePath());
             Statement statement = connection.createStatement()) {
            statement.execute("VACUUM INTO '" + escaped + "'");
        }
    }

    private static void verifyDatabase(Path candidate) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + candidate.toAbsolutePath().normalize());
             Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("PRAGMA integrity_check")) {
                if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1))) {
                    throw new IOException("SQLite integrity check failed.");
                }
            }
            Set<String> tables = new HashSet<>();
            try (ResultSet result = statement.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='table'")) {
                while (result.next()) tables.add(result.getString(1));
            }
            if (!tables.containsAll(REQUIRED_COLUMNS.keySet())) {
                throw new IOException("Database is missing required MechMotor tables.");
            }
            for (Map.Entry<String, Set<String>> required : REQUIRED_COLUMNS.entrySet()) {
                Set<String> columns = new HashSet<>();
                try (ResultSet result = statement.executeQuery("PRAGMA table_info('" + required.getKey() + "')")) {
                    while (result.next()) columns.add(result.getString("name"));
                }
                if (!columns.containsAll(required.getValue())) {
                    throw new IOException("Database table '" + required.getKey() + "' is missing required columns.");
                }
            }
        }
    }

    private static Path uniqueBackupPath(Path directory, String baseName) {
        Path candidate = directory.resolve(baseName + ".db");
        int suffix = 1;
        while (Files.exists(candidate)) {
            candidate = directory.resolve(baseName + "-" + suffix++ + ".db");
        }
        return candidate;
    }

    private static void moveNewFile(Path source, Path destination) throws IOException {
        // Never replace an existing user backup if another process creates the
        // same timestamped filename between name selection and the move.
        Files.move(source, destination);
    }

    private static void replaceFile(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
