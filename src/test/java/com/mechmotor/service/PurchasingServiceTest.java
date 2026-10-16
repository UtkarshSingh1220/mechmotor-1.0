package com.mechmotor.service;

import com.mechmotor.domain.PartDraft;
import com.mechmotor.domain.PurchaseDraft;
import com.mechmotor.domain.PurchaseReceipt;
import com.mechmotor.domain.SupplierDraft;
import com.mechmotor.repository.PartRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
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
        try (Connection connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new org.springframework.core.io.ClassPathResource("schema.sql"));
        }
        jdbc = new JdbcTemplate(source);
        parts = new PartRepository(jdbc);
        purchasing = new PurchasingService(jdbc, parts, new DataSourceTransactionManager(source));
        supplierId = parts.createSupplier(new SupplierDraft("Central Auto", "9876543210", "SUP-1"));
        partId = parts.create(new PartDraft("PUR-001", null, null, "Air filter",
                "Brand", "Company", "Paper", "White", null, supplierId, "10.00", "25.00", 3, 2));
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
        assertEquals(5, jdbc.queryForObject("SELECT quantity FROM stock_movements WHERE reference=?", Integer.class, receipt.purchaseNumber()));
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
