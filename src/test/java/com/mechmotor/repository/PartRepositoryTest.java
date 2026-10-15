package com.mechmotor.repository;

import com.mechmotor.domain.InventoryFilter;
import com.mechmotor.domain.InventoryPage;
import com.mechmotor.domain.Part;
import com.mechmotor.domain.PartDraft;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PartRepositoryTest {
    @TempDir Path tempDir;
    private JdbcTemplate jdbc;
    private PartRepository repository;
    private long brakesCategory;
    private long supplier;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("inventory-test.db").toAbsolutePath());
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
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements WHERE part_id=?", Integer.class, id));

        repository.deactivate(id);
        assertTrue(repository.search("Edited Pad").isEmpty());
    }

    private PartDraft draft(String sku, String name, int quantity, String price, Long categoryId, Long supplierId) {
        return new PartDraft(sku, null, null, name, "Brand", "Company", "Ceramic", "Black",
                categoryId, supplierId, "10.00", price, quantity, 2);
    }
}
