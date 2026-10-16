package com.mechmotor.service;

import com.mechmotor.domain.InventoryFilter;
import com.mechmotor.domain.PartDraft;
import com.mechmotor.domain.Part;
import com.mechmotor.domain.SupplierDraft;
import com.mechmotor.repository.PartRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.*;

class InventoryCsvServiceTest {
    @TempDir Path tempDir;
    private PartRepository repository;
    private JdbcTemplate jdbc;
    private InventoryCsvService csvService;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource source = new SQLiteDataSource(config);
        source.setUrl("jdbc:sqlite:" + tempDir.resolve("csv-test.db").toAbsolutePath());
        try (Connection connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new org.springframework.core.io.ClassPathResource("schema.sql"));
        }
        jdbc = new JdbcTemplate(source);
        repository = new PartRepository(jdbc);
        csvService = new InventoryCsvService(repository, new DataSourceTransactionManager(source));
    }

    @Test
    void exportsAllFilteredRowsAndEscapesCsvFields() throws Exception {
        repository.create(new PartDraft("=2+2", "123,456", "PN-1", "Brake, \"Premium\"",
                "Brand", "Company", "Ceramic", "Black", null, null, "10.00", "25.50", 4, 1));
        repository.create(new PartDraft("FILTER-1", null, null, "Air filter",
                "Brand", "Company", "Paper", "White", null, null, "2.00", "5.00", 2, 1));

        Path output = tempDir.resolve("inventory.csv");
        long count = csvService.export(output, InventoryFilter.defaults("brake"));
        String csv = Files.readString(output);

        assertEquals(1, count);
        assertTrue(csv.startsWith("\uFEFFSKU,Barcode,Part Number"));
        assertTrue(csv.contains("\"123,456\""));
        assertTrue(csv.contains("\"Brake, \"\"Premium\"\"\""));
        assertTrue(csv.contains("Reorder Level,Purchase Price INR,Selling Price INR"));
        assertTrue(csv.contains("1,10.00,25.50"));
        assertTrue(csv.contains("25.50"));
        assertFalse(csv.contains("Air filter"));
    }

    @Test
    void importsCsvWithQuotedFieldsAndPreservesPricesAndStock() throws Exception {
        repository.createCategory("Brakes");
        repository.createSupplier(new SupplierDraft("Central Auto", "9876543210", "SUP-CSV"));
        Path input = tempDir.resolve("import.csv");
        Files.writeString(input, "\uFEFFSKU,Barcode,Part Number,Name,Brand,Company,Material,Color,Category,Supplier,Quantity,Reorder Level,Purchase Price INR,Selling Price INR\r\n"
                + "CSV-1,\"123,456\",PN-1,\"Brake, \"\"Premium\"\"\",Brand,Company,Ceramic,Black,Brakes,Central Auto,4,2,10.25,25.50\r\n");

        assertEquals(1, csvService.importFrom(input));
        Part imported = repository.search("CSV-1").get(0);
        assertEquals("Brake, \"Premium\"", imported.name());
        assertEquals(4, imported.quantity());
        assertEquals(2, imported.reorderLevel());
        assertEquals(1025L, imported.purchasePricePaise());
        assertEquals(2550L, imported.sellingPricePaise());
    }

    @Test
    void failedImportRollsBackAllRowsWhenSkuAlreadyExists() throws Exception {
        repository.create(new PartDraft("DUP-1", null, null, "Existing part",
                null, null, null, null, null, null, "1.00", "2.00", 1, 0));
        Path input = tempDir.resolve("duplicate.csv");
        Files.writeString(input, "SKU,Barcode,Part Number,Name,Brand,Company,Material,Color,Category,Supplier,Quantity,Reorder Level,Purchase Price INR,Selling Price INR\r\n"
                + "NEW-1,,,New part,,,,,,,3,0,1.00,2.00\r\n"
                + "DUP-1,,,Duplicate part,,,,,,,3,0,1.00,2.00\r\n");

        assertThrows(IllegalStateException.class, () -> csvService.importFrom(input));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM parts WHERE sku='NEW-1'", Integer.class));
    }

    @Test
    void rejectsNonCsvDestination() {
        assertThrows(IllegalArgumentException.class,
                () -> csvService.export(tempDir.resolve("inventory.db"), InventoryFilter.defaults(null)));
    }
}
