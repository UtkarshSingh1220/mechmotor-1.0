package com.mechmotor.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies the idempotent baseline to fresh/legacy databases once, then applies
 * ordered V2+ SQL migrations. Startup fails closed if an upgrade cannot be
 * completed or the resulting schema is incomplete.
 */
@Component
public final class DatabaseMigrationRunner implements ApplicationRunner {
    private static final Pattern MIGRATION_NAME = Pattern.compile("V(\\d+)__.*\\.sql");
    private static final Map<String, Map<String, String>> LEGACY_COLUMNS = legacyColumns();
    private static final Map<String, List<String>> REQUIRED_COLUMNS = requiredColumns();

    private final DataSource dataSource;

    public DatabaseMigrationRunner(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            createVersionTable(connection);
            int version = currentVersion(connection);
            if (version == 0) {
                applyBaseline(connection);
                version = 1;
            }
            for (Resource migration : loadMigrations()) {
                int migrationVersion = migrationVersion(migration);
                if (migrationVersion <= version) continue;
                if (migrationVersion != version + 1) {
                    throw new IllegalStateException("Database migration sequence is incomplete. Expected V"
                            + (version + 1) + " but found V" + migrationVersion + ".");
                }
                applyMigration(connection, migration, migrationVersion);
                version = migrationVersion;
            }
            verifyCurrentSchema(connection, version);
        }
    }

    private static void createVersionTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS schema_version (
                    version INTEGER PRIMARY KEY,
                    applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        }
    }

    private static int currentVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COALESCE(MAX(version),0) FROM schema_version")) {
            result.next();
            return result.getInt(1);
        }
    }

    private void applyBaseline(Connection connection) throws Exception {
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            // The original release used schema.sql on every startup. Additive
            // columns are added first so its index statements also work on legacy DBs.
            ensureLegacyColumns(connection);
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
            normalizeLegacyTimestamps(connection);
            createCompatibilityIndexes(connection);
            verifyCurrentSchema(connection, 1);
            recordVersion(connection, 1);
            connection.commit();
        } catch (Exception ex) {
            rollbackQuietly(connection);
            throw new IllegalStateException(
                    "MechMotor could not safely initialize or upgrade its database. No migration was committed. "
                            + "Keep the database file unchanged, make a copy, and inspect the nested error before retrying.",
                    ex);
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    private static void ensureLegacyColumns(Connection connection) throws SQLException {
        for (Map.Entry<String, Map<String, String>> table : LEGACY_COLUMNS.entrySet()) {
            if (!tableExists(connection, table.getKey())) continue;
            for (Map.Entry<String, String> column : table.getValue().entrySet()) {
                if (!columnExists(connection, table.getKey(), column.getKey())) {
                    String sql = "ALTER TABLE \"" + table.getKey() + "\" ADD COLUMN \""
                            + column.getKey() + "\" " + column.getValue();
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(sql);
                    }
                }
            }
        }
    }

    private static void normalizeLegacyTimestamps(Connection connection) throws SQLException {
        execute(connection, "UPDATE parts SET updated_at=CURRENT_TIMESTAMP WHERE updated_at IS NULL OR updated_at=''");
        execute(connection, "UPDATE stock_movements SET created_at=CURRENT_TIMESTAMP WHERE created_at IS NULL OR created_at=''");
        execute(connection, """
            CREATE TRIGGER IF NOT EXISTS trg_parts_legacy_updated_at
            AFTER INSERT ON parts
            WHEN NEW.updated_at IS NULL OR NEW.updated_at=''
            BEGIN
              UPDATE parts SET updated_at=CURRENT_TIMESTAMP WHERE id=NEW.id;
            END
            """);
        execute(connection, """
            CREATE TRIGGER IF NOT EXISTS trg_stock_movements_legacy_created_at
            AFTER INSERT ON stock_movements
            WHEN NEW.created_at IS NULL OR NEW.created_at=''
            BEGIN
              UPDATE stock_movements SET created_at=CURRENT_TIMESTAMP WHERE id=NEW.id;
            END
            """);
    }

    private static void createCompatibilityIndexes(Connection connection) throws SQLException {
        ensureNoDuplicateValues(connection, "parts", "sku", true,
                "Duplicate SKUs differ only by case. Resolve them in a copied database before upgrading.");
        ensureNoDuplicateValues(connection, "parts", "barcode", false,
                "Duplicate non-empty barcodes exist. Resolve them in a copied database before upgrading.");
        ensureNoDuplicateValues(connection, "suppliers", "supplier_code", false,
                "Duplicate non-empty supplier codes exist. Resolve them in a copied database before upgrading.");
        execute(connection, "CREATE UNIQUE INDEX IF NOT EXISTS idx_parts_sku_case_insensitive ON parts(sku COLLATE NOCASE)");
        execute(connection, "CREATE UNIQUE INDEX IF NOT EXISTS idx_parts_barcode_legacy_unique ON parts(barcode) WHERE barcode IS NOT NULL AND trim(barcode)<>''");
        execute(connection, "CREATE UNIQUE INDEX IF NOT EXISTS idx_suppliers_code_legacy_unique ON suppliers(supplier_code) WHERE supplier_code IS NOT NULL AND trim(supplier_code)<>''");
    }

    private static void ensureNoDuplicateValues(Connection connection, String table, String column,
                                                boolean includeEmpty, String message) throws SQLException {
        String predicate = includeEmpty ? column + " IS NOT NULL" : column + " IS NOT NULL AND trim(" + column + ")<>''";
        String sql = "SELECT 1 FROM \"" + table + "\" WHERE " + predicate
                + " GROUP BY \"" + column + "\" COLLATE NOCASE HAVING COUNT(*)>1 LIMIT 1";
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            if (result.next()) throw new IllegalStateException(message);
        }
    }

    private void applyMigration(Connection connection, Resource migration, int version) throws Exception {
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            ScriptUtils.executeSqlScript(connection, migration);
            verifyCurrentSchema(connection, version);
            recordVersion(connection, version);
            connection.commit();
        } catch (Exception ex) {
            rollbackQuietly(connection);
            throw new IllegalStateException("Database migration V" + version + " failed (" + migration.getFilename()
                    + "). The migration was rolled back.", ex);
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    private static void recordVersion(Connection connection, int version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO schema_version(version) VALUES (?)")) {
            statement.setInt(1, version);
            statement.executeUpdate();
        }
    }

    private static List<Resource> loadMigrations() throws Exception {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/V*__*.sql");
        List<Resource> migrations = new ArrayList<>();
        for (Resource resource : resources) {
            if (resource.getFilename() != null && MIGRATION_NAME.matcher(resource.getFilename()).matches()) {
                migrations.add(resource);
            }
        }
        migrations.sort(Comparator.comparingInt(DatabaseMigrationRunner::migrationVersionUnchecked));
        return migrations;
    }

    private static int migrationVersion(Resource resource) {
        Matcher matcher = MIGRATION_NAME.matcher(resource.getFilename());
        if (!matcher.matches()) throw new IllegalArgumentException("Invalid migration filename: " + resource.getFilename());
        return Integer.parseInt(matcher.group(1));
    }

    private static int migrationVersionUnchecked(Resource resource) {
        return migrationVersion(resource);
    }

    private static void verifyCurrentSchema(Connection connection, int version) throws SQLException {
        for (Map.Entry<String, List<String>> table : REQUIRED_COLUMNS.entrySet()) {
            if (!tableExists(connection, table.getKey())) {
                throw new IllegalStateException("Database is missing required table '" + table.getKey() + "'.");
            }
            for (String column : table.getValue()) {
                if (!columnExists(connection, table.getKey(), column)) {
                    throw new IllegalStateException("Database table '" + table.getKey()
                            + "' is missing required column '" + column + "'.");
                }
            }
        }
        if (version >= 2) {
            if (!tableExists(connection, "purchase_cancellations")) {
                throw new IllegalStateException("Database is missing required table 'purchase_cancellations'.");
            }
            for (String column : List.of("id", "purchase_id", "reason", "created_at")) {
                if (!columnExists(connection, "purchase_cancellations", column)) {
                    throw new IllegalStateException("Database table 'purchase_cancellations' is missing required column '" + column + "'.");
                }
            }
            if (!columnExists(connection, "purchase_items", "previous_purchase_price_paise")) {
                throw new IllegalStateException("Database table 'purchase_items' is missing required column 'previous_purchase_price_paise'.");
            }
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static boolean columnExists(Connection connection, String table, String column) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(\"" + table + "\")")) {
            while (result.next()) {
                if (column.equalsIgnoreCase(result.getString("name"))) return true;
            }
            return false;
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void rollbackQuietly(Connection connection) {
        try { connection.rollback(); } catch (SQLException ignored) { /* preserve original failure */ }
    }

    private static Map<String, Map<String, String>> legacyColumns() {
        Map<String, Map<String, String>> tables = new LinkedHashMap<>();
        tables.put("categories", columns(
                "parent_id", "INTEGER REFERENCES categories(id)",
                "active", "INTEGER NOT NULL DEFAULT 1 CHECK(active IN (0,1))"));
        tables.put("suppliers", columns(
                "phone", "TEXT",
                "supplier_code", "TEXT"));
        tables.put("parts", columns(
                "barcode", "TEXT",
                "part_number", "TEXT",
                "brand", "TEXT",
                "company", "TEXT",
                "material", "TEXT",
                "color", "TEXT",
                "category_id", "INTEGER REFERENCES categories(id)",
                "supplier_id", "INTEGER REFERENCES suppliers(id)",
                "purchase_price_paise", "INTEGER NOT NULL DEFAULT 0 CHECK(purchase_price_paise >= 0)",
                "selling_price_paise", "INTEGER NOT NULL DEFAULT 0 CHECK(selling_price_paise >= 0)",
                "active", "INTEGER NOT NULL DEFAULT 1 CHECK(active IN (0,1))",
                "updated_at", "TEXT NOT NULL DEFAULT ''"));
        tables.put("inventory", columns(
                "reserved_quantity", "INTEGER NOT NULL DEFAULT 0 CHECK(reserved_quantity >= 0)",
                "reorder_level", "INTEGER NOT NULL DEFAULT 0 CHECK(reorder_level >= 0)"));
        tables.put("stock_movements", columns(
                "movement_type", "TEXT NOT NULL DEFAULT 'ADJUSTMENT'",
                "reference", "TEXT",
                "reason", "TEXT",
                "created_at", "TEXT NOT NULL DEFAULT ''"));
        return Map.copyOf(tables);
    }

    private static Map<String, List<String>> requiredColumns() {
        Map<String, List<String>> tables = new LinkedHashMap<>();
        tables.put("categories", List.of("id", "name", "parent_id", "active"));
        tables.put("suppliers", List.of("id", "name", "phone", "supplier_code"));
        tables.put("parts", List.of("id", "sku", "barcode", "part_number", "name", "brand", "company",
                "material", "color", "category_id", "supplier_id", "purchase_price_paise", "selling_price_paise", "active", "updated_at"));
        tables.put("inventory", List.of("part_id", "quantity_on_hand", "reserved_quantity", "reorder_level"));
        tables.put("stock_movements", List.of("id", "part_id", "movement_type", "quantity", "reference", "reason", "created_at"));
        tables.put("part_fitments", List.of("id", "part_id", "make", "model", "year_from", "year_to", "engine", "trim", "notes"));
        tables.put("saved_views", List.of("id", "name", "query", "brand", "material", "color", "category_id", "supplier_id",
                "stock_status", "min_quantity", "max_quantity", "min_selling_price_paise", "max_selling_price_paise", "sort_by", "ascending", "created_at", "updated_at"));
        tables.put("sales", List.of("id", "sale_number", "customer_name", "subtotal_paise", "discount_paise", "total_paise", "status", "created_at"));
        tables.put("sale_items", List.of("id", "sale_id", "part_id", "sku_snapshot", "name_snapshot", "quantity", "unit_price_paise", "line_total_paise"));
        tables.put("payments", List.of("id", "sale_id", "method", "amount_paise", "reference", "created_at"));
        tables.put("purchases", List.of("id", "purchase_number", "supplier_id", "supplier_name_snapshot", "supplier_invoice_reference", "subtotal_paise", "status", "created_at"));
        tables.put("purchase_items", List.of("id", "purchase_id", "part_id", "sku_snapshot", "name_snapshot", "quantity", "unit_cost_paise", "line_total_paise"));
        tables.put("sale_voids", List.of("id", "sale_id", "reason", "amount_refunded_paise", "refund_method", "refund_reference", "created_at"));
        tables.put("sale_returns", List.of("id", "return_number", "sale_id", "sale_item_id", "quantity", "refund_paise", "refund_method", "refund_reference", "reason", "created_at"));
        return Map.copyOf(tables);
    }

    private static Map<String, String> columns(String... pairs) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put(pairs[i], pairs[i + 1]);
        return result;
    }
}
