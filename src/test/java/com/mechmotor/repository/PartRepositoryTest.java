package com.mechmotor.repository;

import com.mechmotor.domain.InventoryFilter;
import com.mechmotor.domain.InventoryPage;
import com.mechmotor.domain.Part;
import com.mechmotor.domain.PartDraft;
import com.mechmotor.domain.SupplierDraft;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

class PartRepositoryTest {
    @TempDir Path tempDir;
    private JdbcTemplate jdbc;
    private DataSource dataSource;
    private PartRepository repository;
    private long brakesCategory;
    private long supplier;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource testDataSource = new SQLiteDataSource(config);
        testDataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("inventory-test.db").toAbsolutePath());
        dataSource = testDataSource;
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new org.springframework.core.io.ClassPathResource("schema.sql"));
        }
        jdbc = new JdbcTemplate(dataSource);
        repository = new PartRepository(jdbc);
        jdbc.update("INSERT INTO categories(name) VALUES ('Brakes')");
        brakesCategory = jdbc.queryForObject("SELECT id FROM categories WHERE name='Brakes'", Long.class);
        jdbc.update("INSERT INTO suppliers(name) VALUES ('North Supply')");
        supplier = jdbc.queryForObject("SELECT id FROM suppliers WHERE name='North Supply'", Long.class);
    }

    @Test
    void createsAndSearchesPartCaseInsensitivelyWithExactSkuRankedFirst() {
        repository.create(draft("ZZ-OTHER", "Other Pad", 2, "100.00", null, null));
        repository.create(draft("BRK-001", "Brake Pad", 4, "125.50", brakesCategory, supplier));
        repository.create(draft("ZZ-EXTRA", "BRK-001 replacement", 1, "125.50", null, null));

        InventoryPage exact = repository.findPage(InventoryFilter.defaults("brk-001"));
        assertEquals(2, exact.totalItems());
        assertEquals("BRK-001", exact.items().get(0).sku());

        InventoryPage filtered = repository.findPage(new InventoryFilter("BRAKE", null, null, null,
                brakesCategory, supplier, InventoryFilter.StockStatus.IN_STOCK,
                1, 10, 10000L, 20000L, 0, 20, "sku", true));
        assertEquals(1, filtered.totalItems());
        assertEquals("Brake Pad", filtered.items().get(0).name());
    }

    @Test
    void escapesSqlWildcardsAsLiteralSearchCharacters() {
        repository.create(draft("FILTER-1", "Brake % Filter", 1, "10.00", null, null));
        repository.create(draft("FILTER-2", "Brake Air Filter", 1, "10.00", null, null));

        InventoryPage result = repository.findPage(InventoryFilter.defaults("%"));

        assertEquals(1, result.totalItems());
        assertEquals("FILTER-1", result.items().get(0).sku());
    }

    @Test
    void boundsPageSizeAndSortKey() {
        InventoryFilter filter = new InventoryFilter(null, null, null, null, null, null,
                InventoryFilter.StockStatus.ANY, null, null, null, null,
                -5, 5000, "name; DROP TABLE parts", true);

        assertEquals(0, filter.page());
        assertEquals(200, filter.pageSize());
        assertEquals("name", filter.sortBy());
    }

    @Test
    void transactionRollsBackPartAndInventoryWhenOpeningStockLedgerWriteFails() {
        jdbc.execute("""
            CREATE TRIGGER fail_opening_stock BEFORE INSERT ON stock_movements
            WHEN NEW.reason = 'Opening inventory'
            BEGIN SELECT RAISE(ABORT, 'simulated stock ledger failure'); END
            """);

        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        assertThrows(org.springframework.dao.DataAccessException.class, () ->
                transaction.execute(status -> repository.create(
                        draft("ROLLBACK-1", "Rollback part", 3, "10.00", null, null))));

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM parts WHERE sku='ROLLBACK-1'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM inventory", Integer.class));
    }

    @Test
    void supplierContactDetailsCanBeCreatedAndUpdatedWithoutChangingSupplierId() {
        long id = repository.createSupplier(new SupplierDraft("Central Auto", "9876543210", "SUP-001"));
        assertEquals("9876543210", repository.supplierDetails(id).phone());
        assertEquals("SUP-001", repository.supplierDetails(id).supplierCode());

        repository.updateSupplier(id, new SupplierDraft("Central Auto Parts", "9876500000", "SUP-001"));
        assertEquals(id, repository.supplierDetails(id).id());
        assertEquals("Central Auto Parts", repository.supplierDetails(id).name());
        assertEquals("9876500000", repository.supplierDetails(id).phone());
    }

    @Test
    void duplicateSupplierCreationDoesNotEraseExistingContactDetails() {
        long id = repository.createSupplier(new SupplierDraft("Existing Supplier", "9999999999", "KEEP-1"));
        assertEquals(id, repository.createSupplier(" existing   supplier "));
        assertEquals("9999999999", repository.supplierDetails(id).phone());
        assertEquals("KEEP-1", repository.supplierDetails(id).supplierCode());
    }

    @Test
    void renamingSupplierToAnotherExistingNameIsRejected() {
        long first = repository.createSupplier("Supplier Alpha");
        repository.createSupplier("Supplier Beta");
        assertThrows(IllegalArgumentException.class,
                () -> repository.updateSupplier(first, new SupplierDraft("Supplier Beta", null, null)));
        assertEquals("Supplier Alpha", repository.supplierDetails(first).name());
    }

    @Test
    void duplicateSupplierCodeIsRejected() {
        repository.createSupplier(new SupplierDraft("Supplier One", "1111111111", "CODE-1"));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> repository.createSupplier(new SupplierDraft("Supplier Two", "2222222222", "CODE-1")));
    }

    @Test
    void nestedCategoriesDisplayTheirFullPathAndCanBeRenamed() {
        long parent = repository.createCategory("Engine");
        long child = repository.createCategory("Filters", parent);

        assertTrue(repository.categories().stream().anyMatch(o -> o.id() == parent && o.name().equals("Engine")));
        assertTrue(repository.categories().stream().anyMatch(o -> o.id() == child && o.name().equals("Engine / Filters")));

        repository.renameCategory(child, "Oil filters");
        assertTrue(repository.categories().stream().anyMatch(o -> o.id() == child && o.name().equals("Engine / Oil filters")));
        repository.renameCategory(parent, "Powertrain");
        assertTrue(repository.categories().stream().anyMatch(o -> o.id() == child && o.name().equals("Powertrain / Oil filters")));
    }

    @Test
    void categoryDeactivationProtectsActiveChildrenAndInventoryReferences() {
        long parent = repository.createCategory("Electrical");
        long child = repository.createCategory("Bulbs", parent);
        assertThrows(IllegalArgumentException.class, () -> repository.deactivateCategory(parent));
        repository.deactivateCategory(child);
        assertTrue(repository.categories().stream().noneMatch(o -> o.id() == child));

        long usedCategory = repository.createCategory("Cooling");
        repository.create(draft("COOL-001", "Radiator", 1, "100.00", usedCategory, null));
        assertThrows(IllegalArgumentException.class, () -> repository.deactivateCategory(usedCategory));
    }

    @Test
    void duplicateSupplierNamesReuseExistingSupplierCaseInsensitively() {
        long duplicateId = repository.createSupplier("  north   supply  ");

        assertEquals(supplier, duplicateId);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM suppliers WHERE name = ? COLLATE NOCASE",
                Integer.class, "NORTH SUPPLY"));

        long createdId = repository.createSupplier("Local Parts");
        assertEquals(createdId, repository.createSupplier("local parts"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM suppliers WHERE name = ? COLLATE NOCASE",
                Integer.class, "local parts"));
    }

    @Test
    void duplicateSkuIsRejectedByDatabaseConstraint() {
        repository.create(draft("DUP-1", "First", 0, "0", null, null));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> repository.create(draft("DUP-1", "Second", 0, "0", null, null)));
    }

    @Test
    void updateRecordsStockAdjustmentAndDeactivateHidesPartFromSearch() {
        long id = repository.create(draft("EDIT-1", "Edited Pad", 3, "22.00", null, null));
        repository.update(id, new PartDraft("EDIT-1", null, "PN-2", "Edited Pad V2",
                "Brand", "Company", "Ceramic", "Black", brakesCategory, supplier,
                "10.00", "25.00", 5, 2));
        assertEquals(5, repository.findById(id).quantity());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements WHERE part_id=?", Integer.class, id));

        repository.deactivate(id);
        assertTrue(repository.search("Edited Pad").isEmpty());
    }

    private PartDraft draft(String sku, String name, int quantity, String price, Long categoryId, Long supplierId) {
        return new PartDraft(sku, null, null, name, "Brand", "Company", "Ceramic", "Black",
                categoryId, supplierId, "10.00", price, quantity, 2);
    }
}
