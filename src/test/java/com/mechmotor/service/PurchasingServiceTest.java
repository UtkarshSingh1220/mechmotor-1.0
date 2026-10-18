package com.mechmotor.service;

import com.mechmotor.domain.PartDraft;
import com.mechmotor.domain.PurchaseDraft;
import com.mechmotor.domain.FormalPurchaseOrderDraft;
import com.mechmotor.domain.PurchaseReceipt;
import com.mechmotor.domain.PurchaseOrderDraft;
import com.mechmotor.domain.PurchaseLineDraft;
import com.mechmotor.domain.PurchaseOrderReceiveLine;
import com.mechmotor.domain.SupplierReturnDraft;
import com.mechmotor.domain.SupplierReturnLineDraft;
import com.mechmotor.domain.SupplierDraft;
import com.mechmotor.domain.SupplierPaymentDraft;
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
    void multiItemReceiptPostsAllLinesAndStockAtomically() {
        long secondPart = parts.create(new PartDraft("PUR-002", null, null, "Oil filter",
                "Brand", "Company", "Paper", "Black", null, supplierId, "5.00", "12.00", 4, 1));
        var receipt = purchasing.receive(new PurchaseOrderDraft(supplierId, "INV-MULTI",
                java.util.List.of(new PurchaseLineDraft(partId, 5, 1250), new PurchaseLineDraft(secondPart, 2, 600))));
        assertEquals(2, receipt.lineCount());
        assertEquals(7, receipt.units());
        assertEquals(7450L, receipt.totalPaise());
        assertEquals(8, parts.findById(partId).quantity());
        assertEquals(6, parts.findById(secondPart).quantity());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM purchase_items WHERE purchase_id=?", Integer.class, receipt.purchaseId()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements WHERE reference=? AND movement_type='PURCHASE'", Integer.class, receipt.purchaseNumber()));
    }

    @Test
    void multiItemReceiptRollsBackEveryLineWhenOneLedgerWriteFails() {
        long secondPart = parts.create(new PartDraft("PUR-003", null, null, "Cabin filter",
                "Brand", "Company", "Paper", "Grey", null, supplierId, "5.00", "12.00", 4, 1));
        jdbc.execute("CREATE TRIGGER fail_second_purchase BEFORE INSERT ON stock_movements WHEN NEW.movement_type='PURCHASE' AND NEW.part_id="
                + secondPart + " BEGIN SELECT RAISE(ABORT, 'simulated second-line failure'); END");
        assertThrows(DataAccessException.class, () -> purchasing.receive(new PurchaseOrderDraft(supplierId, null,
                java.util.List.of(new PurchaseLineDraft(partId, 2, 1000), new PurchaseLineDraft(secondPart, 3, 500)))));
        assertEquals(3, parts.findById(partId).quantity());
        assertEquals(4, parts.findById(secondPart).quantity());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM purchases", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM purchase_items", Integer.class));
    }

    @Test
    void multiItemDraftRejectsDuplicatePartIdsAndEmptyReceipts() {
        assertThrows(IllegalArgumentException.class, () -> new PurchaseOrderDraft(supplierId, null,
                java.util.List.of(new PurchaseLineDraft(partId, 1, 100), new PurchaseLineDraft(partId, 2, 200))));
        assertThrows(IllegalArgumentException.class, () -> new PurchaseOrderDraft(supplierId, null, java.util.List.of()));
    }

    @Test
    void cancellationCannotReduceOnHandBelowReservedQuantity() {
        PurchaseReceipt receipt = purchasing.receive(new PurchaseDraft(partId, 5, 1250, supplierId, null));
        jdbc.update("UPDATE inventory SET reserved_quantity=4 WHERE part_id=?", partId);
        assertThrows(IllegalStateException.class, () -> purchasing.cancelPurchase(receipt.purchaseNumber(), "Stock reserved for order"));
        assertEquals(8, parts.findById(partId).quantity());
        assertEquals("RECEIVED", jdbc.queryForObject("SELECT status FROM purchases WHERE id=?", String.class, receipt.purchaseId()));
    }

    @Test
    void supplierReturnReducesStockAndPayableAndTracksCreditNote() {
        var receipt = purchasing.receive(new PurchaseDraft(partId, 5, 1250, supplierId, "INV-RETURN"));
        var payment = purchasing.recordSupplierPayment(new SupplierPaymentDraft(
                receipt.purchaseNumber(), 1000, SupplierPaymentDraft.Method.BANK_TRANSFER, "UTR-RET-1"));
        assertEquals(5250L, payment.outstandingPaise());

        Long purchaseItemId = jdbc.queryForObject("SELECT id FROM purchase_items WHERE purchase_id=?",
                Long.class, receipt.purchaseId());
        var returned = purchasing.recordSupplierReturn(new SupplierReturnDraft(receipt.purchaseNumber(),
                "Damaged filter", "CN-2026-18",
                java.util.List.of(new SupplierReturnLineDraft(purchaseItemId, 2))));

        assertTrue(returned.returnNumber().startsWith("RET-"));
        assertEquals(2, returned.unitsReturned());
        assertEquals(2500L, returned.totalPaise());
        assertEquals(2750L, returned.outstandingPaise());
        assertEquals(6, parts.findById(partId).quantity());
        assertEquals(-2, jdbc.queryForObject("SELECT quantity FROM stock_movements WHERE reference=? AND movement_type='SUPPLIER_RETURN'",
                Integer.class, returned.returnNumber()));
        assertEquals(1, purchasing.supplierReturns(receipt.purchaseNumber()).size());
        assertEquals(2750L, purchasing.balance(receipt.purchaseNumber()).outstandingPaise());
        assertEquals(1, purchasing.history(receipt.purchaseNumber(), 10).size());
        assertEquals(2750L, purchasing.history(receipt.purchaseNumber(), 10).get(0).outstandingPaise());
    }

    @Test
    void supplierReturnRejectsOverReturnAndReservedStockWithoutPartialWrites() {
        var receipt = purchasing.receive(new PurchaseDraft(partId, 2, 1250, supplierId, "INV-RETURN-GUARD"));
        Long purchaseItemId = jdbc.queryForObject("SELECT id FROM purchase_items WHERE purchase_id=?",
                Long.class, receipt.purchaseId());
        assertThrows(IllegalArgumentException.class, () -> purchasing.recordSupplierReturn(
                new SupplierReturnDraft(receipt.purchaseNumber(), "Wrong item", null,
                        java.util.List.of(new SupplierReturnLineDraft(purchaseItemId, 3)))));
        jdbc.update("UPDATE inventory SET reserved_quantity=5 WHERE part_id=?", partId);
        assertThrows(IllegalStateException.class, () -> purchasing.recordSupplierReturn(
                new SupplierReturnDraft(receipt.purchaseNumber(), "Reserved", null,
                        java.util.List.of(new SupplierReturnLineDraft(purchaseItemId, 1)))));
        assertEquals(5, parts.findById(partId).quantity());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM supplier_returns", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements WHERE movement_type='SUPPLIER_RETURN'",
                Integer.class));
    }

    @Test
    void supplierReturnCreditCanExceedOutstandingAndBlocksFurtherPaymentAndReceiptCancellation() {
        var receipt = purchasing.receive(new PurchaseDraft(partId, 3, 1250, supplierId, "INV-CREDIT-ONLY"));
        Long purchaseItemId = jdbc.queryForObject("SELECT id FROM purchase_items WHERE purchase_id=?",
                Long.class, receipt.purchaseId());
        var returned = purchasing.recordSupplierReturn(new SupplierReturnDraft(receipt.purchaseNumber(),
                "All units damaged", "CN-FULL",
                java.util.List.of(new SupplierReturnLineDraft(purchaseItemId, 3))));
        assertEquals(0, returned.outstandingPaise());
        assertEquals(3, parts.findById(partId).quantity());
        assertThrows(IllegalStateException.class, () -> purchasing.recordSupplierPayment(new SupplierPaymentDraft(
                receipt.purchaseNumber(), 100, SupplierPaymentDraft.Method.CASH, null)));
        assertThrows(IllegalStateException.class,
                () -> purchasing.cancelPurchase(receipt.purchaseNumber(), "Already returned through credit note"));
    }

    @Test
    void supplierReturnCannotCreateUnrepresentedCreditAfterInvoiceIsFullyPaid() {
        var receipt = purchasing.receive(new PurchaseDraft(partId, 2, 1250, supplierId, "INV-PAID-RETURN"));
        var payment = purchasing.recordSupplierPayment(new SupplierPaymentDraft(
                receipt.purchaseNumber(), 2500, SupplierPaymentDraft.Method.BANK_TRANSFER, "UTR-PAID-RETURN"));
        assertEquals(0L, payment.outstandingPaise());
        int stockBefore = parts.findById(partId).quantity();
        Long purchaseItemId = jdbc.queryForObject("SELECT id FROM purchase_items WHERE purchase_id=?",
                Long.class, receipt.purchaseId());

        assertThrows(IllegalStateException.class, () -> purchasing.recordSupplierReturn(
                new SupplierReturnDraft(receipt.purchaseNumber(), "Return after full payment", "CN-PAID-RETURN",
                        java.util.List.of(new SupplierReturnLineDraft(purchaseItemId, 1)))));

        assertEquals(stockBefore, parts.findById(partId).quantity());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM supplier_returns WHERE purchase_id=?",
                Integer.class, receipt.purchaseId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements WHERE movement_type='SUPPLIER_RETURN'",
                Integer.class));
        assertEquals(0L, purchasing.balance(receipt.purchaseNumber()).outstandingPaise());
    }

    @Test
    void supplierReturnFailureRollsBackCreditNoteStockAndLedger() {
        var receipt = purchasing.receive(new PurchaseDraft(partId, 2, 1250, supplierId, "INV-RETURN-ROLLBACK"));
        Long purchaseItemId = jdbc.queryForObject("SELECT id FROM purchase_items WHERE purchase_id=?",
                Long.class, receipt.purchaseId());
        jdbc.execute("CREATE TRIGGER fail_supplier_return_ledger BEFORE INSERT ON stock_movements "
                + "WHEN NEW.movement_type='SUPPLIER_RETURN' BEGIN SELECT RAISE(ABORT, 'simulated supplier return failure'); END");
        assertThrows(DataAccessException.class, () -> purchasing.recordSupplierReturn(
                new SupplierReturnDraft(receipt.purchaseNumber(), "Rollback", "CN-ROLLBACK",
                        java.util.List.of(new SupplierReturnLineDraft(purchaseItemId, 1)))));
        assertEquals(5, parts.findById(partId).quantity());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM supplier_returns", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM supplier_return_items", Integer.class));
    }

    @Test
    void supplierPaymentsTrackPartialBalanceAndVoidingRestoresPayable() {
        PurchaseReceipt receipt = purchasing.receive(new PurchaseDraft(partId, 2, 1250, supplierId, "INV-PAY"));
        var payment = purchasing.recordSupplierPayment(new SupplierPaymentDraft(
                receipt.purchaseNumber(), 1000, SupplierPaymentDraft.Method.BANK_TRANSFER, "UTR-100"));
        assertEquals(1000L, payment.paidTotalPaise());
        assertEquals(1500L, payment.outstandingPaise());
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE supplier_payments SET amount_paise=1 WHERE id=?", payment.paymentId()));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "DELETE FROM supplier_payments WHERE id=?", payment.paymentId()));
        assertEquals(1000L, purchasing.balance(receipt.purchaseNumber()).paidPaise());
        assertEquals(1500L, purchasing.balance(receipt.purchaseNumber()).outstandingPaise());
        assertEquals(1, purchasing.supplierPayments(receipt.purchaseNumber()).size());

        var voided = purchasing.voidSupplierPayment(payment.paymentId(), "Incorrect amount entered");
        assertEquals(0L, voided.paidTotalPaise());
        assertEquals(2500L, voided.outstandingPaise());
        assertEquals("VOIDED", purchasing.supplierPayments(receipt.purchaseNumber()).get(0).status());
    }

    @Test
    void supplierPaymentsCannotOverpayOrDuplicateActiveReference() {
        PurchaseReceipt receipt = purchasing.receive(new PurchaseDraft(partId, 2, 1250, supplierId, null));
        purchasing.recordSupplierPayment(new SupplierPaymentDraft(receipt.purchaseNumber(), 1000,
                SupplierPaymentDraft.Method.UPI, "UPI-REF-1"));
        assertThrows(IllegalArgumentException.class, () -> purchasing.recordSupplierPayment(
                new SupplierPaymentDraft(receipt.purchaseNumber(), 1501, SupplierPaymentDraft.Method.CASH, null)));
        Long purchaseId = jdbc.queryForObject("SELECT id FROM purchases WHERE purchase_number=?", Long.class, receipt.purchaseNumber());
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "INSERT INTO supplier_payments(purchase_id,method,amount_paise,status) VALUES (?,'CASH',1501,'POSTED')", purchaseId));
        assertThrows(DataAccessException.class, () -> purchasing.recordSupplierPayment(
                new SupplierPaymentDraft(receipt.purchaseNumber(), 1000, SupplierPaymentDraft.Method.UPI, "UPI-REF-1")));
        assertEquals(1000L, purchasing.balance(receipt.purchaseNumber()).paidPaise());
        assertEquals(1500L, purchasing.balance(receipt.purchaseNumber()).outstandingPaise());
    }

    @Test
    void receiptCannotBeCancelledWhileSupplierPaymentIsPosted() {
        PurchaseReceipt receipt = purchasing.receive(new PurchaseDraft(partId, 2, 1250, supplierId, null));
        purchasing.recordSupplierPayment(new SupplierPaymentDraft(receipt.purchaseNumber(), 500,
                SupplierPaymentDraft.Method.CASH, null));
        assertThrows(IllegalStateException.class,
                () -> purchasing.cancelPurchase(receipt.purchaseNumber(), "Incorrect receipt"));
        assertEquals("RECEIVED", jdbc.queryForObject("SELECT status FROM purchases WHERE id=?", String.class, receipt.purchaseId()));
        assertEquals(5, parts.findById(partId).quantity());
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

    @Test
    void formalPurchaseOrderDoesNotChangeStockUntilItIsReceived() {
        var order = purchasing.createPurchaseOrder(new FormalPurchaseOrderDraft(supplierId,
                "2026-11-01", "Restock before month end",
                java.util.List.of(new PurchaseLineDraft(partId, 5, 1250))));
        assertTrue(order.orderNumber().startsWith("ORD-"));
        assertEquals("ORDERED", order.status());
        assertEquals(3, parts.findById(partId).quantity(), "Creating an order must not change inventory.");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order_items WHERE purchase_order_id=?",
                Integer.class, order.id()));

        var received = purchasing.receivePurchaseOrder(order.orderNumber(), "INV-FORMAL-1");
        assertEquals(order.orderNumber(), received.orderNumber());
        assertEquals(5, received.units());
        assertEquals(6250L, received.totalPaise());
        assertEquals(8, parts.findById(partId).quantity());
        assertEquals("RECEIVED", purchasing.purchaseOrders(order.orderNumber(), 10).get(0).status());
        assertEquals(received.receiptNumber(), purchasing.purchaseOrders(order.orderNumber(), 10).get(0).receiptNumber());
        assertThrows(IllegalStateException.class,
                () -> purchasing.receivePurchaseOrder(order.orderNumber(), "INV-DUPLICATE"));
    }

    @Test
    void duplicateSupplierInvoiceReferenceCannotBeReceivedTwiceForSameSupplier() {
        var firstOrder = purchasing.createPurchaseOrder(new FormalPurchaseOrderDraft(supplierId, null, null,
                java.util.List.of(new PurchaseLineDraft(partId, 5, 1250))));
        var firstReceipt = purchasing.receivePurchaseOrder(firstOrder.orderNumber(), "SUP-INV-2026-01");
        assertEquals(5, firstReceipt.units());
        assertEquals(8, parts.findById(partId).quantity());

        var secondOrder = purchasing.createPurchaseOrder(new FormalPurchaseOrderDraft(supplierId, null, null,
                java.util.List.of(new PurchaseLineDraft(partId, 2, 1250))));

        assertThrows(IllegalArgumentException.class,
                () -> purchasing.receivePurchaseOrder(secondOrder.orderNumber(), "  sup-inv-2026-01  "));

        assertEquals(8, parts.findById(partId).quantity(),
                "A duplicate supplier invoice must not add stock twice.");
        assertEquals("ORDERED", purchasing.purchaseOrders(secondOrder.orderNumber(), 10).get(0).status(),
                "The rejected receipt must leave the second order open.");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM purchases WHERE supplier_id=? AND supplier_invoice_reference=?",
                Integer.class, supplierId, "SUP-INV-2026-01"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_movements WHERE reference=? AND movement_type='PURCHASE'",
                Integer.class, firstReceipt.receiptNumber()));
    }

    @Test
    void formalPurchaseOrderSupportsMultiplePartialReceiptsAndBlocksOverReceipt() {
        var order = purchasing.createPurchaseOrder(new FormalPurchaseOrderDraft(supplierId, null, null,
                java.util.List.of(new PurchaseLineDraft(partId, 5, 1250))));

        var first = purchasing.receivePurchaseOrder(order.orderNumber(), "INV-PARTIAL-1",
                java.util.List.of(new PurchaseOrderReceiveLine(partId, 2)));
        assertEquals(2, first.units());
        assertEquals(5, parts.findById(partId).quantity());
        var partial = purchasing.purchaseOrders(order.orderNumber(), 10).get(0);
        assertEquals("PARTIALLY_RECEIVED", partial.status());
        assertTrue(partial.receiptNumber().contains(first.receiptNumber()));
        var line = purchasing.purchaseOrderLines(order.orderNumber()).get(0);
        assertEquals(5, line.orderedQuantity());
        assertEquals(2, line.receivedQuantity());
        assertEquals(3, line.remainingQuantity());

        assertThrows(IllegalArgumentException.class, () -> purchasing.receivePurchaseOrder(order.orderNumber(),
                "INV-OVER", java.util.List.of(new PurchaseOrderReceiveLine(partId, 4))));
        assertThrows(IllegalStateException.class,
                () -> purchasing.cancelPurchaseOrder(order.orderNumber(), "Cannot cancel after partial receipt"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM purchases", Integer.class));

        var second = purchasing.receivePurchaseOrder(order.orderNumber(), "INV-PARTIAL-2",
                java.util.List.of(new PurchaseOrderReceiveLine(partId, 3)));
        assertEquals(3, second.units());
        assertEquals(8, parts.findById(partId).quantity());
        var complete = purchasing.purchaseOrders(order.orderNumber(), 10).get(0);
        assertEquals("RECEIVED", complete.status());
        assertTrue(complete.receiptNumber().contains(first.receiptNumber()));
        assertTrue(complete.receiptNumber().contains(second.receiptNumber()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order_receipts WHERE purchase_order_id=?",
                Integer.class, order.id()));
        assertEquals(5, jdbc.queryForObject("SELECT received_quantity FROM purchase_order_items WHERE purchase_order_id=?",
                Integer.class, order.id()));
    }

    @Test
    void partialPurchaseOrderReceiptFailureRollsBackAllRowsAndStock() {
        long secondPart = parts.create(new PartDraft("PUR-PO-PARTIAL-ROLLBACK", null, null, "Cabin filter",
                "Brand", "Company", "Paper", "Grey", null, supplierId, "5.00", "12.00", 4, 1));
        var order = purchasing.createPurchaseOrder(new FormalPurchaseOrderDraft(supplierId, null, null,
                java.util.List.of(new PurchaseLineDraft(partId, 2, 1000),
                        new PurchaseLineDraft(secondPart, 3, 500))));
        jdbc.execute("CREATE TRIGGER fail_partial_order_receipt BEFORE INSERT ON stock_movements WHEN NEW.movement_type='PURCHASE' AND NEW.part_id="
                + secondPart + " BEGIN SELECT RAISE(ABORT, 'simulated partial receipt failure'); END");

        assertThrows(DataAccessException.class, () -> purchasing.receivePurchaseOrder(order.orderNumber(), "INV-PARTIAL-ROLLBACK",
                java.util.List.of(new PurchaseOrderReceiveLine(partId, 1), new PurchaseOrderReceiveLine(secondPart, 1))));

        assertEquals(3, parts.findById(partId).quantity());
        assertEquals(4, parts.findById(secondPart).quantity());
        assertEquals("ORDERED", purchasing.purchaseOrders(order.orderNumber(), 10).get(0).status());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM purchases", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order_receipts", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT SUM(received_quantity) FROM purchase_order_items WHERE purchase_order_id=?",
                Integer.class, order.id()));
    }

    @Test
    void formalPurchaseOrderCancellationRequiresReasonAndNeverChangesStock() {
        var order = purchasing.createPurchaseOrder(new FormalPurchaseOrderDraft(supplierId, null, null,
                java.util.List.of(new PurchaseLineDraft(partId, 2, 1000))));
        assertThrows(IllegalArgumentException.class,
                () -> purchasing.cancelPurchaseOrder(order.orderNumber(), " "));
        var cancelled = purchasing.cancelPurchaseOrder(order.orderNumber(), "Supplier discontinued item");
        assertEquals("CANCELLED", cancelled.status());
        assertEquals("Supplier discontinued item", cancelled.cancellationReason());
        assertEquals(3, parts.findById(partId).quantity());
        assertThrows(IllegalStateException.class,
                () -> purchasing.receivePurchaseOrder(order.orderNumber(), null));
        assertThrows(IllegalStateException.class,
                () -> purchasing.cancelPurchaseOrder(order.orderNumber(), "Second cancellation"));
    }

    @Test
    void formalPurchaseOrderRejectsDuplicatePartsAndInvalidExpectedDate() {
        assertThrows(IllegalArgumentException.class, () -> new FormalPurchaseOrderDraft(supplierId, "01/11/2026", null,
                java.util.List.of(new PurchaseLineDraft(partId, 1, 100))));
        assertThrows(IllegalArgumentException.class, () -> new FormalPurchaseOrderDraft(supplierId, null, null,
                java.util.List.of(new PurchaseLineDraft(partId, 1, 100), new PurchaseLineDraft(partId, 2, 200))));
    }

    @Test
    void formalPurchaseOrderReceiptFailureRollsBackStockReceiptAndOrderStatus() {
        long secondPart = parts.create(new PartDraft("PUR-PO-ROLLBACK", null, null, "Cabin filter",
                "Brand", "Company", "Paper", "Grey", null, supplierId, "5.00", "12.00", 4, 1));
        var order = purchasing.createPurchaseOrder(new FormalPurchaseOrderDraft(supplierId, null, null,
                java.util.List.of(new PurchaseLineDraft(partId, 2, 1000),
                        new PurchaseLineDraft(secondPart, 3, 500))));
        jdbc.execute("CREATE TRIGGER fail_formal_order_receipt BEFORE INSERT ON stock_movements WHEN NEW.movement_type='PURCHASE' AND NEW.part_id="
                + secondPart + " BEGIN SELECT RAISE(ABORT, 'simulated formal order receipt failure'); END");

        assertThrows(DataAccessException.class,
                () -> purchasing.receivePurchaseOrder(order.orderNumber(), "INV-ROLLBACK"));

        assertEquals(3, parts.findById(partId).quantity());
        assertEquals(4, parts.findById(secondPart).quantity());
        assertEquals("ORDERED", purchasing.purchaseOrders(order.orderNumber(), 10).get(0).status());
        assertEquals("", purchasing.purchaseOrders(order.orderNumber(), 10).get(0).receiptNumber());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM purchases", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM purchase_items", Integer.class));
    }
}
