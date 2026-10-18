import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Windows CI acceptance helper for a legacy shop database.
 * Usage: java -cp "target/dist/*" tools/acceptance/WindowsDatabaseLifecycle.java seed|verify
 */
public class WindowsDatabaseLifecycle {
    private static Path databasePath() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isBlank()) {
            throw new IllegalStateException("LOCALAPPDATA is unavailable on the Windows runner.");
        }
        return Path.of(localAppData, "MechMotor", "data", "mechmotor.db").toAbsolutePath().normalize();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected seed or verify.");
        if ("seed".equalsIgnoreCase(args[0])) seed();
        else if ("verify".equalsIgnoreCase(args[0])) verify();
        else throw new IllegalArgumentException("Unknown command: " + args[0]);
    }

    private static void seed() throws Exception {
        Path file = databasePath();
        Files.createDirectories(file.getParent());
        Files.deleteIfExists(file.resolveSibling(file.getFileName() + "-wal"));
        Files.deleteIfExists(file.resolveSibling(file.getFileName() + "-shm"));
        Files.deleteIfExists(file);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = connection.createStatement()) {
            // Representative pre-versioned schema from an older installed release.
            statement.execute("CREATE TABLE categories(id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)");
            statement.execute("CREATE TABLE suppliers(id INTEGER PRIMARY KEY, name TEXT NOT NULL)");
            statement.execute("""
                CREATE TABLE parts(
                  id INTEGER PRIMARY KEY, sku TEXT NOT NULL UNIQUE, part_number TEXT,
                  name TEXT NOT NULL, brand TEXT, company TEXT, material TEXT, color TEXT,
                  category_id INTEGER REFERENCES categories(id), supplier_id INTEGER REFERENCES suppliers(id)
                )
                """);
            statement.execute("CREATE TABLE inventory(part_id INTEGER PRIMARY KEY, quantity_on_hand INTEGER NOT NULL DEFAULT 0)");
            statement.execute("CREATE TABLE stock_movements(id INTEGER PRIMARY KEY, part_id INTEGER NOT NULL, quantity INTEGER NOT NULL, reason TEXT)");
            statement.execute("INSERT INTO categories(id,name) VALUES (41,'Legacy Brakes')");
            statement.execute("INSERT INTO suppliers(id,name) VALUES (51,'Legacy Supplier')");
            statement.execute("""
                INSERT INTO parts(id,sku,part_number,name,brand,company,material,color,category_id,supplier_id)
                VALUES (61,'UPGRADE-LEGACY-001','BP-OLD-1','Legacy Brake Pad','OldBrand','OldCo','Ceramic','Black',41,51)
                """);
            statement.execute("INSERT INTO inventory(part_id,quantity_on_hand) VALUES (61,17)");
            statement.execute("INSERT INTO stock_movements(id,part_id,quantity,reason) VALUES (71,61,-3,'Legacy stock correction')");
        }
        System.out.println("Seeded legacy database at " + file);
    }

    private static void verify() throws Exception {
        Path file = databasePath();
        if (!Files.isRegularFile(file)) throw new IllegalStateException("User database was removed: " + file);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = connection.createStatement()) {
            try (ResultSet row = statement.executeQuery("""
                    SELECT p.sku,p.name,p.category_id,p.supplier_id,c.name AS category_name,s.name AS supplier_name
                    FROM parts p JOIN categories c ON c.id=p.category_id
                    JOIN suppliers s ON s.id=p.supplier_id WHERE p.id=61
                    """)) {
                if (!row.next()) throw new IllegalStateException("Legacy part was lost during install/upgrade.");
                require("UPGRADE-LEGACY-001".equals(row.getString("sku")), "Legacy SKU changed.");
                require("Legacy Brake Pad".equals(row.getString("name")), "Legacy part name changed.");
                require(row.getInt("category_id") == 41, "Legacy category relationship was lost.");
                require(row.getInt("supplier_id") == 51, "Legacy supplier relationship was lost.");
                require("Legacy Brakes".equals(row.getString("category_name")), "Legacy category was lost.");
                require("Legacy Supplier".equals(row.getString("supplier_name")), "Legacy supplier was lost.");
            }
            try (ResultSet row = statement.executeQuery("SELECT quantity_on_hand FROM inventory WHERE part_id=61")) {
                if (!row.next() || row.getInt(1) != 17) throw new IllegalStateException("Legacy inventory quantity was not preserved.");
            }
            try (ResultSet row = statement.executeQuery("SELECT quantity,reason,created_at,movement_type FROM stock_movements WHERE id=71")) {
                if (!row.next()) throw new IllegalStateException("Legacy stock ledger entry was lost.");
                require(row.getInt("quantity") == -3, "Legacy stock movement quantity changed.");
                require("Legacy stock correction".equals(row.getString("reason")), "Legacy stock movement reason changed.");
                require(row.getString("created_at") != null && !row.getString("created_at").isBlank(), "Legacy stock movement timestamp was not migrated.");
                require("ADJUSTMENT".equals(row.getString("movement_type")), "Legacy movement type was not normalized.");
            }
            try (ResultSet row = statement.executeQuery("SELECT MAX(version) FROM schema_version")) {
                if (!row.next() || row.getInt(1) != 9) throw new IllegalStateException("Database did not reach schema version 9.");
            }
            try (ResultSet row = statement.executeQuery("PRAGMA foreign_key_check")) {
                if (row.next()) throw new IllegalStateException("Upgrade left a broken foreign-key relationship.");
            }
        }
        System.out.println("Verified migrated user data at " + file);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
