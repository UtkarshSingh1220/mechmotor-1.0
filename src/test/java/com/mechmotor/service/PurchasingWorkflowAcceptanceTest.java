package com.mechmotor.service;

import com.mechmotor.config.DatabaseMigrationRunner;
import com.mechmotor.domain.FormalPurchaseOrderDraft;
import com.mechmotor.domain.PartDraft;
import com.mechmotor.domain.PurchaseLineDraft;
import com.mechmotor.domain.PurchaseOrderReceiveLine;
import com.mechmotor.domain.SupplierDraft;
import com.mechmotor.domain.SupplierPaymentDraft;
import com.mechmotor.domain.SupplierReturnDraft;
import com.mechmotor.domain.SupplierReturnLineDraft;
import com.mechmotor.repository.PartRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PurchasingWorkflowAcceptanceTest {
    @TempDir Path tempDir;

    private JdbcTemplate jdbc;
    private PartRepository parts;
    private PurchasingService purchasing;
    private OperationalReportsService reports;
    private long partId;
    private long supplierId;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource source = new SQLiteDataSource(config);
        source.setUrl("jdbc:sqlite:" + tempDir.resolve("purchasing-acceptance.db").toAbsolutePath());
        new DatabaseMigrationRunner(source).run(new DefaultApplicationArguments(new String[0]));
        jdbc = new JdbcTemplate(source);
        parts = new PartRepository(jdbc);
        purchasing = new PurchasingService(jdbc, parts, new DataSourceTransactionManager(source));
        reports = new OperationalReportsService(jdbc);

        supplierId = parts.createSupplier(new SupplierDraft("Central Auto", "9876543210", "SUP-E2E"));
        partId = parts.create(new PartDraft("E2E-001", null, null, "Air filter",
                "Brand", "Company", "Paper", "White", null, supplierId, "10.00", "25.00", 3, 2));
    }

    @Test
    void purchaseOrderPartialReceiptsSupplierCreditPaymentAndPayablesReportReconcileEndToEnd() throws Exception {
        var order = purchasing.createPurchaseOrder(new FormalPurchaseOrderDraft(supplierId,
                "2026-11-01", "Phase 5 reconciliation acceptance",
                List.of(new PurchaseLineDraft(partId, 5, 1250))));

        assertEquals("ORDERED", order.status());
        assertEquals(3, parts.findById(partId).quantity(),
                "Creating a purchase order must not change on-hand stock.");
        assertEquals(6250L, order.totalPaise());

        var firstReceipt = purchasing.receivePurchaseOrder(order.orderNumber(), "SUP-INV-001",
                List.of(new PurchaseOrderReceiveLine(partId, 2)));
        assertEquals(2500L, firstReceipt.totalPaise());
        assertEquals(5, parts.findById(partId).quantity());
        assertEquals("PARTIALLY_RECEIVED", purchasing.purchaseOrders(order.orderNumber(), 10).get(0).status());

        var secondReceipt = purchasing.receivePurchaseOrder(order.orderNumber(), "SUP-INV-002",
                List.of(new PurchaseOrderReceiveLine(partId, 3)));
        assertEquals(3750L, secondReceipt.totalPaise());
        assertEquals(8, parts.findById(partId).quantity());
        assertEquals("RECEIVED", purchasing.purchaseOrders(order.orderNumber(), 10).get(0).status());

        Long secondPurchaseId = jdbc.queryForObject(
                "SELECT id FROM purchases WHERE purchase_number=?", Long.class, secondReceipt.receiptNumber());
        Long secondPurchaseItemId = jdbc.queryForObject(
                "SELECT id FROM purchase_items WHERE purchase_id=? AND part_id=?", Long.class,
                secondPurchaseId, partId);

        var supplierReturn = purchasing.recordSupplierReturn(new SupplierReturnDraft(
                secondReceipt.receiptNumber(), "One damaged air filter", "CN-E2E-001",
                List.of(new SupplierReturnLineDraft(secondPurchaseItemId, 1))));
        assertEquals(1250L, supplierReturn.totalPaise());
        assertEquals(2500L, supplierReturn.outstandingPaise());
        assertEquals(7, parts.findById(partId).quantity());

        var payment = purchasing.recordSupplierPayment(new SupplierPaymentDraft(
                secondReceipt.receiptNumber(), 2000, SupplierPaymentDraft.Method.BANK_TRANSFER, "UTR-E2E-001"));
        assertEquals(500L, payment.outstandingPaise());
        assertEquals(500L, purchasing.balance(secondReceipt.receiptNumber()).outstandingPaise());

        Path report = tempDir.resolve("supplier-payables.csv");
        assertEquals(2, reports.export(OperationalReportsService.ReportType.SUPPLIER_PAYABLES, report,
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1)));
        String csv = Files.readString(report, StandardCharsets.UTF_8);
        assertTrue(csv.contains("\""+ firstReceipt.receiptNumber() +"\""), "First delivery must be in the report.");
        assertTrue(csv.contains("\""+ secondReceipt.receiptNumber() +"\""), "Second delivery must be in the report.");
        assertTrue(csv.contains("\"25.00\",\"0.00\",\"0.00\",\"25.00\""),
                "First delivery should remain fully payable.");
        assertTrue(csv.contains("\"37.50\",\"12.50\",\"20.00\",\"5.00\""),
                "Second delivery should reconcile original cost, credit note, posted payment, and balance.");

        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order_receipts WHERE purchase_order_id=?",
                Integer.class, order.id()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM supplier_returns WHERE purchase_id=?",
                Integer.class, secondPurchaseId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM supplier_payments WHERE purchase_id=? AND status='POSTED'",
                Integer.class, secondPurchaseId));
        assertEquals(5, jdbc.queryForObject("SELECT SUM(quantity) FROM purchase_order_items WHERE purchase_order_id=?",
                Integer.class, order.id()));
    }
}
