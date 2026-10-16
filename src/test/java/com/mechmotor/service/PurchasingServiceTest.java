package com.mechmotor.service;

import com.mechmotor.domain.PartDraft;
import com.mechmotor.domain.PurchaseDraft;
import com.mechmotor.domain.PurchaseReceipt;
import com.mechmotor.domain.SupplierDraft;
import com.mechmotor.config.DatabaseMigrationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import com.mechmotor.repository.PartRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.*;

class PurchasingServiceTest {
    @TempDir Path tempDir;
    private JdbcTemplate jdbc;
    private PartRepository parts;
    private PurchasingService purchasing;
    private long partId;
    private long supplierId;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource source = new SQLiteDataSource(config);
        source.setUrl("jdbc:sqlite:" + tempDir.resolve("purchase-test.db").toAbsolutePath());
        new DatabaseMigrationRunner(source).run(new DefaultApplicationArguments(new String[0]));
        jdbc = new JdbcTemplate(source);
        parts = new PartRepository(jdbc);
        purchasing = new PurchasingService(jdbc, parts, new DataSourceTransactionManager(source));
        supplierId = parts.createSupplier(new SupplierDraft("Central Auto", "9876543210", "SUP-1"));
        partId = parts.create(new PartDraft("PUR-001", null, null, "Air filter",
                "Brand", "Company", "Paper", "White", null, supplierId, "10.00", "25.00", 3, 2));
    }

    @Test
    void cancellingReceiptReversesStockAndRestoresPriorCostOnce() {
        PurchaseReceipt receipt = purchasing.receive(new PurchaseDraft(partId, 5, 1250, supplierId, "INV-CANCEL"));
        assertEquals(8, parts.findById(partId).quantity());
        assertEquals(1250L, parts.findDetailsById(partId).purchasePricePaise());

        var cancelled = purchasing.cancelPurchase(receipt.purchaseNumber(), "Supplier sent incorrect stock");

        assertEquals(5, cancelled.unitsReversed());
        assertEquals(6250L, cancelled.totalPaise());
        assertEquals(3, parts.findById(partId).quantity());
        assertEquals(1000L, parts.findDetailsById(partId).purchasePricePaise());
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM purchases WHERE id=?", String.class, receipt.purchaseId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM purchase_cancellations WHERE purchase_id=?", Integer.class, receipt.purchaseId()));
        assertEquals(-5, jdbc.queryForObject("SELECT quantity FROM stock_movements WHERE reference=? AND movement_type='REVERSAL'",
                Integer.class, receipt.purchaseNumber()));
        var history = purchasing.history(receipt.purchaseNumber().toLowerCase(java.util.Locale.ROOT), 500);
        assertEquals(1, history.size());
        assertEquals("CANCELLED", history.get(0).status());
        assertEquals("Supplier sent incorrect stock", history.get(0).cancellationReason());

        assertThrows(IllegalStateException.class,
                () -> purchasing.cancelPurchase(receipt.purchaseNumber(), "Duplicate cancellation"));
        assertEquals(3, parts.findById(partId).quantity());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM purchase_cancellations WHERE purchase_id=?", Integer.class, receipt.purchaseId()));
    }

    @Test
    void cancellationIsRejectedWhenAvailableStockCannotCoverReceipt() {
        PurchaseReceipt receipt = purchasing.receive(new PurchaseDraft(partId, 5, 1250, supplierId, null));
        jdbc.update("UPDATE inventory SET quantity_on_hand=4 WHERE part_id=?", partId);

        assertThrows(IllegalStateException.class,
                () -> purchasing.cancelPurchase(receipt.purchaseNumber(), "Stock already consumed"));

        assertEquals(4, parts.findById(partId).quantity());
        assertEquals("RECEIVED", jdbc.queryForObject("SELECT status FROM purchases WHERE id=?", String.class, receipt.purchaseId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM purchase_cancellations", Integer.class));
    }

    @Test
    void cancellationLedgerFailureRollsBackStatusStockCostAndAudit() {
        PurchaseReceipt receipt = purchasing.receive(new PurchaseDraft(partId, 5, 1250, supplierId, null));
        jdbc.execute("""
            CREATE TRIGGER fail_purchase_reversal BEFORE INSERT ON stock_movements
            WHEN NEW.movement_type='REVERSAL'
            BEGIN SELECT RAISE(ABORT, 'simulated purchase reversal failure'); END
            """);

        assertThrows(DataAccessException.class,
                () -> purchasing.cancelPurchase(receipt.purchaseNumber(), "Rollback test"));

        assertEquals(8, parts.findById(partId).quantity());
        assertEquals(1250L, parts.findDetailsById(partId).purchasePricePaise());
        assertEquals("RECEIVED", jdbc.queryForObject("SELECT status FROM purchases WHERE id=?", String.class, receipt.purchaseId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM purchase_cancellations", Integer.class));
    }

    @Test
    void purchaseHistorySearchEscapesWildcardsAndCapsResults() {
        PurchaseReceipt receipt = purchasing.receive(new PurchaseDraft(partId, 1, 1250, supplierId, "INV%2026"));
        assertEquals(receipt.purchaseNumber(), purchasing.history("INV%", 5000).get(0).purchaseNumber());
        assertTrue(purchasing.history("_2026", 500).isEmpty());
        assertEquals(1, purchasing.history(null, 0).size());
        assertThrows(IllegalArgumentException.class, () -> purchasing.history("x".repeat(121), 10));
    }

    @Test
    void receivingStockCreatesPurchaseSnapshotsUpdatesCostAndWritesLedger() {
        PurchaseReceipt receipt = purchasing.receive(new PurchaseDraft(partId, 5, 1250, supplierId, "INV-2026-44"));

        assertTrue(receipt.purchaseNumber().startsWith("PO-"));
        assertEquals(6250L, receipt.totalPaise());
        assertEquals(8, parts.findById(partId).quantity());
        assertEquals(1250L, parts.findDetailsById(partId).purchasePricePaise());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM purchases WHERE id=?", Integer.class, receipt.purchaseId()));
        assertEquals("INV-2026-44", jdbc.queryForObject("SELECT supplier_invoice_reference FROM purchases WHERE id=?", String.class, receipt.purchaseId()));
        assertEquals(5, jdbc.queryForObject("SELECT quantity FROM purchase_items WHERE purchase_id=?", Integer.class, receipt.purchaseId()));
        assertEquals(1000L, jdbc.queryForObject("SELECT previous_purchase_price_paise FROM purchase_items WHERE purchase_id=?",
                Long.class, receipt.purchaseId()));
        assertEquals(5, jdbc.queryForObject("SELECT quantity FROM stock_movements WHERE reference=?", Integer.class, receipt.purchaseNumber()));
    }

    @Test
    void cancellingLegacyReceiptDoesNotGuessUnknownPreviousPurchaseCost() {
        jdbc.update("UPDATE parts SET purchase_price_paise=1500 WHERE id=?", partId);
        jdbc.update("""
            INSERT INTO purchases(purchase_number,supplier_id,supplier_name_snapshot,supplier_invoice_reference,subtotal_paise,status)
            VALUES ('PO-LEGACY-1',?,?,?,1500,'RECEIVED')
            """, supplierId, "Central Auto", "LEGACY-INVOICE");
        Long purchaseId = jdbc.queryForObject("SELECT id FROM purchases WHERE purchase_number='PO-LEGACY-1'", Long.class);
        jdbc.update("""
            INSERT INTO purchase_items(purchase_id,part_id,sku_snapshot,name_snapshot,quantity,unit_cost_paise,line_total_paise)
            VALUES (?,?,?,?,1,1500,1500)
            """, purchaseId, partId, "PUR-001", "Air filter");

        purchasing.cancelPurchase("PO-LEGACY-1", "Legacy receipt correction");

        assertEquals(2, parts.findById(partId).quantity());
        assertEquals(1500L, parts.findDetailsById(partId).purchasePricePaise());
        assertNull(jdbc.queryForObject("SELECT previous_purchase_price_paise FROM purchase_items WHERE purchase_id=?",
                Long.class, purchaseId));
    }

    @Test
    void invalidSupplierIsRejectedBeforePurchaseIsRecorded() {
        assertThrows(IllegalArgumentException.class,
                () -> purchasing.receive(new PurchaseDraft(partId, 1, 1000, 999999L, null)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM purchases", Integer.class));
    }

    @Test
    void stockLedgerFailureRollsBackPurchaseAndQuantityIncrease() {
        jdbc.execute("""
            CREATE TRIGGER fail_purchase_ledger BEFORE INSERT ON stock_movements
            WHEN NEW.movement_type='PURCHASE'
            BEGIN SELECT RAISE(ABORT, 'simulated purchase ledger failure'); END
            """);
        assertThrows(DataAccessException.class,
                () -> purchasing.receive(new PurchaseDraft(partId, 5, 1250, supplierId, null)));
        assertEquals(3, parts.findById(partId).quantity());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM purchases", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM purchase_items", Integer.class));
    }
}
