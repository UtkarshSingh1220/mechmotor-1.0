package com.mechmotor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class OperationalReportsServiceTest {
    @TempDir Path tempDir;
    private OperationalReportsService reports;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource source = new SQLiteDataSource(config);
        source.setUrl("jdbc:sqlite:" + tempDir.resolve("reports-test.db").toAbsolutePath());
        try (Connection connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        jdbc = new JdbcTemplate(source);
        reports = new OperationalReportsService(jdbc);
    }

    @Test
    void exportsHeaderForEachReportAndDoesNotOverwriteExistingFile() throws Exception {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate through = LocalDate.of(2026, 1, 7);
        for (OperationalReportsService.ReportType type : OperationalReportsService.ReportType.values()) {
            Path target = tempDir.resolve(type.name().toLowerCase() + ".csv");
            assertEquals(0, reports.export(type, target, from, through));
            String csv = Files.readString(target, StandardCharsets.UTF_8);
            assertTrue(csv.startsWith("\uFEFF\""), "CSV should start with a UTF-8 BOM and quoted header");
            assertTrue(csv.contains("\n"), "CSV should contain a header row");
            assertThrows(IllegalArgumentException.class,
                    () -> reports.export(type, target, from, through));
        }
    }

    @Test
    void acceptsExactly366CalendarDaysAndRejectsLongerRangesBeforeWriting() throws Exception {
        Path target = tempDir.resolve("boundary.csv");
        assertEquals(0, reports.export(OperationalReportsService.ReportType.SALES, target,
                LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1)));
        assertTrue(Files.exists(target));

        Path excessive = tempDir.resolve("excessive.csv");
        assertThrows(IllegalArgumentException.class, () -> reports.export(
                OperationalReportsService.ReportType.SALES, excessive,
                LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 2)));
        assertFalse(Files.exists(excessive));
    }

    @Test
    void rejectsReversedDateRangeBeforeWriting() {
        Path target = tempDir.resolve("invalid.csv");
        assertThrows(IllegalArgumentException.class, () -> reports.export(
                OperationalReportsService.ReportType.SALES, target,
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1)));
        assertFalse(Files.exists(target));
    }

    @Test
    void escapesSpreadsheetFormulasButPreservesNegativeMovementQuantities() throws Exception {
        jdbc.update("INSERT INTO parts(sku,name) VALUES (?,?)", "SKU-CSV", "=1+1");
        long partId = jdbc.queryForObject("SELECT id FROM parts WHERE sku=?", Long.class, "SKU-CSV");
        jdbc.update("""
            INSERT INTO stock_movements(part_id,movement_type,quantity,reference,reason,created_at)
            VALUES (?,'REVERSAL',-1,?,?, '2026-10-16 10:00:00')
            """, partId, "=2+2", "@SUM(A1:A2)");

        Path target = tempDir.resolve("movements.csv");
        assertEquals(1, reports.export(OperationalReportsService.ReportType.STOCK_MOVEMENTS, target,
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1)));
        String csv = Files.readString(target, StandardCharsets.UTF_8);
        assertTrue(csv.contains("\"'=1+1\""), "Formula-like part names should be neutralized");
        assertTrue(csv.contains("\"-1\""), "Negative movement quantities must remain numeric");
        assertTrue(csv.contains("\"'=2+2\""), "Formula-like references should be neutralized");
        assertTrue(csv.contains("\"'@SUM(A1:A2)\""), "Formula-like reasons should be neutralized");
    }

    @Test
    void rejectsNonCsvDestination() {
        assertThrows(IllegalArgumentException.class, () -> reports.export(
                OperationalReportsService.ReportType.SALES, tempDir.resolve("report.txt"),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2)));
    }
}
