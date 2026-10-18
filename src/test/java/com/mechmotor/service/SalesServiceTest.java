package com.mechmotor.service;

import com.mechmotor.domain.PartDraft;
import com.mechmotor.domain.SaleDraft;
import com.mechmotor.domain.CartSaleDraft;
import com.mechmotor.domain.SaleLineDraft;
import com.mechmotor.domain.SaleReceipt;
import com.mechmotor.domain.SaleVoidReceipt;
import com.mechmotor.domain.SaleReturnDraft;
import com.mechmotor.domain.SaleReturnable;
import com.mechmotor.domain.SaleReturnReceipt;
import com.mechmotor.repository.PartRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SalesServiceTest {
    @TempDir Path tempDir;
    private JdbcTemplate jdbc;
    private PartRepository parts;
    private SalesService sales;
    private long partId;
    private long secondPartId;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource source = new SQLiteDataSource(config);
        source.setUrl("jdbc:sqlite:" + tempDir.resolve("sales-test.db").toAbsolutePath());
        try (Connection connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new org.springframework.core.io.ClassPathResource("schema.sql"));
        }
        jdbc = new JdbcTemplate(source);
        parts = new PartRepository(jdbc);
        sales = new SalesService(jdbc, parts, new DataSourceTransactionManager(source));
        partId = parts.create(new PartDraft("SALE-001", null, null, "Brake pad",
                "Brand", "Company", "Ceramic", "Black", null, null, "10.00", "25.00", 3, 2));
        secondPartId = parts.create(new PartDraft("SALE-002", null, null, "Oil filter",
                "Brand", "Company", "Paper", "White", null, null, "5.00", "12.00", 4, 1));
    }

    @Test
    void salesHistorySearchShowsPostedAndVoidedSalesWithRefundAudit() {
        SaleReceipt sale = sales.checkout(new SaleDraft(partId, 1, 2500, 0,
                "Utkarsh Walk-in", "UPI", "PAY-REF"));
        var matching = sales.history("utkarsh", 100);
        assertEquals(1, matching.size());
        assertEquals(sale.saleNumber(), matching.get(0).saleNumber());
        assertEquals("POSTED", matching.get(0).status());
        assertEquals(1, matching.get(0).units());
        assertEquals(2500L, matching.get(0).totalPaise());
        assertTrue(matching.get(0).paymentMethods().contains("UPI"));
        assertEquals(0L, matching.get(0).refundedPaise());

        sales.voidSale(sale.saleNumber(), "Customer cancelled", "REF-1");
        var voided = sales.history(sale.saleNumber().toLowerCase(java.util.Locale.ROOT), 100);
        assertEquals(1, voided.size());
        assertEquals("VOIDED", voided.get(0).status());
        assertEquals(2500L, voided.get(0).refundedPaise());
        assertTrue(sales.history("not-a-customer", 100).isEmpty());
    }

    @Test
    void salesHistoryEscapesLikeWildcardsAndCapsResultLimit() {
        SaleReceipt sale = sales.checkout(new SaleDraft(partId, 1, 2500, 0,
                "100% Genuine", "CASH", null));
        assertEquals(sale.saleNumber(), sales.history("100%", 5000).get(0).saleNumber());
        assertTrue(sales.history("_Genuine", 100).isEmpty());
        assertEquals(1, sales.history(null, 0).size());
        assertThrows(IllegalArgumentException.class, () -> sales.history("x".repeat(121), 10));
    }

    @Test
    void checkoutPostsSalePaymentAndStockLedgerAtomically() {
        SaleReceipt receipt = sales.checkout(new SaleDraft(partId, 2, 2500, 500,
                "Walk-in customer", "UPI", "UPI-REF-1"));

        assertTrue(receipt.saleNumber().startsWith("MM-"));
        assertEquals(5000L, receipt.subtotalPaise());
        assertEquals(500L, receipt.discountPaise());
        assertEquals(4500L, receipt.totalPaise());
        assertEquals(1, parts.findById(partId).quantity());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sales WHERE id=?", Integer.class, receipt.saleId()));
        assertEquals(2, jdbc.queryForObject("SELECT quantity FROM sale_items WHERE sale_id=?", Integer.class, receipt.saleId()));
        assertEquals(-2, jdbc.queryForObject("SELECT quantity FROM stock_movements WHERE reference=?", Integer.class, receipt.saleNumber()));
        assertEquals(4500L, jdbc.queryForObject("SELECT amount_paise FROM payments WHERE sale_id=?", Long.class, receipt.saleId()));
    }

    @Test
    void taxExclusiveCheckoutAddsRoundedTaxAndSnapshotsProvisionalLocalClassification() {
        new ShopProfileService(jdbc).save(new ShopProfileService.ShopProfile(
                "Test Shop", "", "", "", "", "06", "GST", 1800, true));
        SaleReceipt receipt = sales.checkout(new SaleDraft(partId, 1, 10000, 1000,
                "Walk-in", "CASH", null));
        assertEquals(10620L, receipt.totalPaise());
        assertEquals(9000L, jdbc.queryForObject("SELECT taxable_base_paise FROM sales WHERE id=?", Long.class, receipt.saleId()));
        assertEquals(1620L, jdbc.queryForObject("SELECT tax_amount_paise FROM sales WHERE id=?", Long.class, receipt.saleId()));
        assertEquals(810L, jdbc.queryForObject("SELECT cgst_paise FROM sales WHERE id=?", Long.class, receipt.saleId()));
        assertEquals(810L, jdbc.queryForObject("SELECT sgst_paise FROM sales WHERE id=?", Long.class, receipt.saleId()));
        assertEquals("PROVISIONAL_LOCAL", jdbc.queryForObject("SELECT tax_classification FROM sales WHERE id=?", String.class, receipt.saleId()));
        assertEquals(10620L, jdbc.queryForObject("SELECT amount_paise FROM payments WHERE sale_id=?", Long.class, receipt.saleId()));
    }

    @Test
    void customerReturnRefundIncludesTheOriginalSavedTaxAmount() {
        new ShopProfileService(jdbc).save(new ShopProfileService.ShopProfile(
                "Test Shop", "", "", "", "", "06", "GST", 500, true));
        SaleReceipt sale = sales.checkout(new SaleDraft(partId, 1, 10000, 0, "Buyer", "CASH", null));
        var returnable = sales.returnableSale(sale.saleNumber()).get(0);
        var returned = sales.returnSale(new SaleReturnDraft(sale.saleNumber(), returnable.saleItemId(),
                1, "CASH", null, "Wrong item"));
        assertEquals(10500L, returned.refundPaise());
    }

    @Test
    void knownDifferentBuyerStateUsesIgstAndSameStateUsesLocalComponents() {
        new ShopProfileService(jdbc).save(new ShopProfileService.ShopProfile(
                "Test Shop", "", "", "", "", "06", "GST", 500, true));
        var interstate = sales.checkout(new CartSaleDraft(List.of(new SaleLineDraft(partId, 1, 10000)),
                0, "Buyer", "UPI", null), "07");
        assertEquals(10500L, interstate.totalPaise());
        assertEquals(500L, jdbc.queryForObject("SELECT igst_paise FROM sales WHERE id=?", Long.class, interstate.saleId()));
        assertEquals("INTERSTATE", jdbc.queryForObject("SELECT tax_classification FROM sales WHERE id=?", String.class, interstate.saleId()));
        var local = sales.checkout(new CartSaleDraft(List.of(new SaleLineDraft(secondPartId, 1, 10000)),
                0, "Buyer", "UPI", null), "06");
        assertEquals(250L, jdbc.queryForObject("SELECT cgst_paise FROM sales WHERE id=?", Long.class, local.saleId()));
        assertEquals(250L, jdbc.queryForObject("SELECT sgst_paise FROM sales WHERE id=?", Long.class, local.saleId()));
        assertEquals("LOCAL", jdbc.queryForObject("SELECT tax_classification FROM sales WHERE id=?", String.class, local.saleId()));
    }

    @Test
    void multiItemCartPostsAllLinesAndDeductsStockAtomically() {
        var receipt = sales.checkout(new CartSaleDraft(List.of(
                new SaleLineDraft(partId, 2, 2500),
                new SaleLineDraft(secondPartId, 3, 1200)),
                400, "Walk-in", "CASH", null));

        assertEquals(8600L, receipt.subtotalPaise());
        assertEquals(400L, receipt.discountPaise());
        assertEquals(8200L, receipt.totalPaise());
        assertEquals(2, receipt.lines().size());
        assertEquals(1, parts.findById(partId).quantity());
        assertEquals(1, parts.findById(secondPartId).quantity());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM sale_items WHERE sale_id=?", Integer.class, receipt.saleId()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements WHERE reference=? AND movement_type='SALE'",
                Integer.class, receipt.saleNumber()));
        assertEquals(8200L, jdbc.queryForObject("SELECT amount_paise FROM payments WHERE sale_id=?", Long.class, receipt.saleId()));
    }

    @Test
    void insufficientStockOnAnyCartLineRollsBackWholeSale() {
        assertThrows(IllegalStateException.class, () -> sales.checkout(new CartSaleDraft(List.of(
                new SaleLineDraft(partId, 1, 2500),
                new SaleLineDraft(secondPartId, 99, 1200)),
                0, null, "CASH", null)));

        assertEquals(3, parts.findById(partId).quantity());
        assertEquals(4, parts.findById(secondPartId).quantity());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sales", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sale_items", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM payments", Integer.class));
    }

    @Test
    void ledgerFailureOnLaterCartLineRollsBackEarlierLinesAndSale() {
        jdbc.execute("CREATE TRIGGER fail_second_sale_ledger BEFORE INSERT ON stock_movements "
                + "WHEN NEW.movement_type='SALE' AND NEW.part_id=" + secondPartId
                + " BEGIN SELECT RAISE(ABORT, 'simulated ledger failure'); END");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> sales.checkout(new CartSaleDraft(List.of(
                new SaleLineDraft(partId, 1, 2500),
                new SaleLineDraft(secondPartId, 1, 1200)),
                0, null, "CASH", null)));

        assertEquals(3, parts.findById(partId).quantity());
        assertEquals(4, parts.findById(secondPartId).quantity());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sales", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sale_items", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements WHERE movement_type='SALE'", Integer.class));
    }

    @Test
    void cartRejectsDuplicatePartsAndEmptyLines() {
        assertThrows(IllegalArgumentException.class, () -> new CartSaleDraft(List.of(
                new SaleLineDraft(partId, 1, 2500), new SaleLineDraft(partId, 2, 2400)),
                0, null, "CASH", null));
        assertThrows(IllegalArgumentException.class, () -> new CartSaleDraft(List.of(),
                0, null, "CASH", null));
    }

    @Test
    void insufficientStockDoesNotCreateSaleOrChangeInventory() {
        assertThrows(IllegalStateException.class, () -> sales.checkout(
                new SaleDraft(partId, 4, 2500, 0, null, "CASH", null)));

        assertEquals(3, parts.findById(partId).quantity());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sales", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM payments", Integer.class));
    }

    @Test
    void voidSaleRestoresStockAndRecordsRefundAndReversalOnce() {
        SaleReceipt sale = sales.checkout(new SaleDraft(partId, 2, 2500, 500,
                "Walk-in customer", "UPI", "UPI-REF-2"));

        SaleVoidReceipt result = sales.voidSale(sale.saleNumber(), "Customer returned item", "REFUND-REF-2");

        assertEquals(2, result.unitsRestored());
        assertEquals(4500L, result.amountRefundedPaise());
        assertEquals("UPI", result.refundMethod());
        assertEquals(3, parts.findById(partId).quantity());
        assertEquals("VOIDED", jdbc.queryForObject("SELECT status FROM sales WHERE id=?", String.class, sale.saleId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sale_voids WHERE sale_id=?", Integer.class, sale.saleId()));
        assertEquals(2, jdbc.queryForObject("SELECT quantity FROM stock_movements WHERE reference=? AND movement_type='REVERSAL'",
                Integer.class, sale.saleNumber()));

        assertThrows(IllegalStateException.class,
                () -> sales.voidSale(sale.saleNumber(), "Duplicate request", null));
        assertEquals(3, parts.findById(partId).quantity());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sale_voids WHERE sale_id=?", Integer.class, sale.saleId()));
    }

    @Test
    void voidRollsBackStatusStockAndAuditIfLedgerWriteFails() {
        SaleReceipt sale = sales.checkout(new SaleDraft(partId, 2, 2500, 0,
                null, "CASH", null));
        jdbc.execute("""
            CREATE TRIGGER fail_sale_reversal BEFORE INSERT ON stock_movements
            WHEN NEW.movement_type='REVERSAL'
            BEGIN SELECT RAISE(ABORT, 'simulated reversal failure'); END
            """);

        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> sales.voidSale(sale.saleNumber(), "Test rollback", null));
        assertEquals(1, parts.findById(partId).quantity());
        assertEquals("POSTED", jdbc.queryForObject("SELECT status FROM sales WHERE id=?", String.class, sale.saleId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sale_voids", Integer.class));
    }


    @Test
    void partialReturnsRefundProportionallyAndCannotReturnMoreThanSold() {
        SaleReceipt sale = sales.checkout(new SaleDraft(partId, 3, 2500, 500,
                "Walk-in", "CASH", null));
        SaleReturnable line = sales.returnableSale(sale.saleNumber()).get(0);
        SaleReturnReceipt first = sales.returnSale(new SaleReturnDraft(sale.saleNumber(), line.saleItemId(),
                1, "UPI", "REF-1", "Wrong item"));
        assertEquals(2333L, first.refundPaise());
        assertEquals(1, parts.findById(partId).quantity());

        SaleReturnable afterFirst = sales.returnableSale(sale.saleNumber()).get(0);
        SaleReturnReceipt second = sales.returnSale(new SaleReturnDraft(sale.saleNumber(), afterFirst.saleItemId(),
                2, "CASH", null, "Customer return"));
        assertEquals(4667L, second.refundPaise());
        assertEquals(3, parts.findById(partId).quantity());
        assertEquals(7000L, jdbc.queryForObject("SELECT SUM(refund_paise) FROM sale_returns WHERE sale_id=?",
                Long.class, sale.saleId()));
        assertThrows(IllegalArgumentException.class, () -> sales.returnSale(new SaleReturnDraft(
                sale.saleNumber(), afterFirst.saleItemId(), 1, "CASH", null, null)));
    }

    @Test
    void multiItemReturnsAllocateSaleDiscountAndRefundExactNetLineTotals() {
        var sale = sales.checkout(new com.mechmotor.domain.CartSaleDraft(List.of(
                new com.mechmotor.domain.SaleLineDraft(partId, 2, 2500),
                new com.mechmotor.domain.SaleLineDraft(secondPartId, 3, 1200)),
                400, "Walk-in", "CASH", null));
        List<SaleReturnable> lines = sales.returnableSale(sale.saleNumber());
        SaleReturnable brake = lines.stream().filter(line -> line.partId() == partId).findFirst().orElseThrow();
        SaleReturnable filter = lines.stream().filter(line -> line.partId() == secondPartId).findFirst().orElseThrow();
        assertEquals(4768L, brake.lineNetTotalPaise());
        assertEquals(3432L, filter.lineNetTotalPaise());

        assertEquals(2384L, sales.returnSale(new SaleReturnDraft(sale.saleNumber(), brake.saleItemId(),
                1, "CASH", null, null)).refundPaise());
        SaleReturnable brakeAfterOne = sales.returnableSale(sale.saleNumber()).stream()
                .filter(line -> line.partId() == partId).findFirst().orElseThrow();
        assertEquals(2384L, sales.returnSale(new SaleReturnDraft(sale.saleNumber(), brakeAfterOne.saleItemId(),
                1, "CASH", null, null)).refundPaise());
        SaleReturnable filterCurrent = sales.returnableSale(sale.saleNumber()).stream()
                .filter(line -> line.partId() == secondPartId).findFirst().orElseThrow();
        assertEquals(3432L, sales.returnSale(new SaleReturnDraft(sale.saleNumber(), filterCurrent.saleItemId(),
                3, "UPI", "REF-ALL", null)).refundPaise());
        assertEquals(8200L, jdbc.queryForObject("SELECT SUM(refund_paise) FROM sale_returns WHERE sale_id=?",
                Long.class, sale.saleId()));
        assertEquals(3, parts.findById(partId).quantity());
        assertEquals(4, parts.findById(secondPartId).quantity());
    }

    @Test
    void returnLedgerFailureRollsBackReturnAndStock() {
        SaleReceipt sale = sales.checkout(new SaleDraft(partId, 2, 2500, 0, null, "CASH", null));
        SaleReturnable line = sales.returnableSale(sale.saleNumber()).get(0);
        jdbc.execute("""
            CREATE TRIGGER fail_return_ledger BEFORE INSERT ON stock_movements
            WHEN NEW.movement_type='RETURN'
            BEGIN SELECT RAISE(ABORT, 'simulated return ledger failure'); END
            """);
        assertThrows(org.springframework.dao.DataAccessException.class, () -> sales.returnSale(
                new SaleReturnDraft(sale.saleNumber(), line.saleItemId(), 1, "CASH", null, "rollback")));
        assertEquals(1, parts.findById(partId).quantity());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sale_returns", Integer.class));
    }

    @Test
    void discountCannotExceedSubtotalAndDoesNotPostSale() {
        assertThrows(IllegalArgumentException.class, () -> sales.checkout(
                new SaleDraft(partId, 1, 2500, 2501, null, "CASH", null)));

        assertEquals(3, parts.findById(partId).quantity());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sales", Integer.class));
    }
}
