package com.mechmotor.service;

import com.mechmotor.domain.InventoryFilter;
import com.mechmotor.domain.PartDraft;
import com.mechmotor.repository.PartRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
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
        JdbcTemplate jdbc = new JdbcTemplate(source);
        repository = new PartRepository(jdbc);
        csvService = new InventoryCsvService(repository);
    }

    @Test
    void exportsAllFilteredRowsAndEscapesCsvFields() throws Exception {
        repository.create(new PartDraft("BRAKE-1", "123,456", "PN-1", "Brake, \"Premium\"",
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
        assertTrue(csv.contains("25.50"));
        assertFalse(csv.contains("Air filter"));
    }

    @Test
    void rejectsNonCsvDestination() {
        assertThrows(IllegalArgumentException.class,
                () -> csvService.export(tempDir.resolve("inventory.db"), InventoryFilter.defaults(null)));
    }
}
