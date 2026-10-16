package com.mechmotor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import com.mechmotor.config.DatabaseMigrationRunner;
import org.springframework.boot.DefaultApplicationArguments;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        new DatabaseMigrationRunner(source).run(new DefaultApplicationArguments(new String[0]));
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
    void supplierPayablesReportReconcilesPostedPaymentsAndExcludesVoidedPayments() throws Exception {
        jdbc.update("INSERT INTO suppliers(name,supplier_code) VALUES (?,?)", "Central Auto", "SUP-RECON");
        long supplierId = jdbc.queryForObject("SELECT id FROM suppliers WHERE supplier_code=?",
                Long.class, "SUP-RECON");
        jdbc.update("""
            INSERT INTO purchases(purchase_number,supplier_id,supplier_name_snapshot,
                supplier_invoice_reference,subtotal_paise,status,created_at)
            VALUES (?,?,?,'INV-1',10000,'RECEIVED','2026-10-10 09:00:00')
            """, "PUR-RECON-1", supplierId, "Central Auto");
        long purchaseId = jdbc.queryForObject("SELECT id FROM purchases WHERE purchase_number=?",
                Long.class, "PUR-RECON-1");
        jdbc.update("INSERT INTO parts(sku,name) VALUES ('SKU-RECON','Air filter')");
        long partId = jdbc.queryForObject("SELECT id FROM parts WHERE sku='SKU-RECON'", Long.class);
        jdbc.update("""
            INSERT INTO purchase_items(purchase_id,part_id,sku_snapshot,name_snapshot,quantity,unit_cost_paise,line_total_paise)
            VALUES (?,?,'SKU-RECON','Air filter',4,2500,10000)
            """, purchaseId, partId);
        long purchaseItemId = jdbc.queryForObject("SELECT id FROM purchase_items WHERE purchase_id=?",Long.class,purchaseId);
        jdbc.update("""
            INSERT INTO supplier_returns(return_number,purchase_id,supplier_id,reason,credit_note_reference,total_paise,status)
            VALUES ('RET-RECON-1',?,?,'Damaged stock','CN-1',2500,'POSTED')
            """,purchaseId,supplierId);
        jdbc.update("""
            INSERT INTO supplier_return_items(supplier_return_id,purchase_item_id,part_id,sku_snapshot,name_snapshot,quantity,unit_cost_paise,line_total_paise)
            SELECT r.id,?,?,'SKU-RECON','Air filter',1,2500,2500 FROM supplier_returns r WHERE r.return_number='RET-RECON-1'
            """,purchaseItemId,partId);
        jdbc.update("""
            INSERT INTO supplier_payments(purchase_id,method,amount_paise,reference,status)
            VALUES (?,'BANK_TRANSFER',2500,'PAY-POSTED','POSTED')
            """, purchaseId);
        jdbc.update("""
            INSERT INTO supplier_payments(purchase_id,method,amount_paise,reference,status,void_reason,voided_at)
            VALUES (?,'BANK_TRANSFER',999,'PAY-VOIDED','VOIDED','Duplicate entry','2026-10-11 09:00:00')
            """, purchaseId);
        jdbc.update("""
            INSERT INTO purchases(purchase_number,supplier_id,supplier_name_snapshot,
                supplier_invoice_reference,subtotal_paise,status,created_at)
            VALUES (?,?,?,'INV-CANCELLED',5000,'CANCELLED','2026-10-12 09:00:00')
            """, "PUR-RECON-2", supplierId, "Central Auto");

        Path target = tempDir.resolve("supplier-payables.csv");
        assertEquals(2, reports.export(OperationalReportsService.ReportType.SUPPLIER_PAYABLES, target,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)));

        String csv = Files.readString(target, StandardCharsets.UTF_8);
        assertTrue(csv.contains("\"PUR-RECON-1\",\"2026-10-10 09:00:00\",\"Central Auto\",\"RECEIVED\",\"100.00\",\"25.00\",\"75.00\""));
        assertTrue(csv.contains("\"PUR-RECON-2\",\"2026-10-12 09:00:00\",\"Central Auto\",\"CANCELLED\",\"0.00\",\"0.00\",\"0.00\""));
        assertFalse(csv.contains("\"999\""), "Voided payment amounts must not be counted as posted payments");
    }

    @Test
    void rejectsNonCsvDestination() {
        assertThrows(IllegalArgumentException.class, () -> reports.export(
                OperationalReportsService.ReportType.SALES, tempDir.resolve("report.txt"),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2)));
    }
}
