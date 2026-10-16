package com.mechmotor.service;

import com.mechmotor.domain.PartDraft;
import com.mechmotor.domain.SaleDraft;
import com.mechmotor.domain.SaleReceipt;
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

import static org.junit.jupiter.api.Assertions.*;

class SalesServiceTest {
    @TempDir Path tempDir;
    private JdbcTemplate jdbc;
    private PartRepository parts;
    private SalesService sales;
    private long partId;

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
    void insufficientStockDoesNotCreateSaleOrChangeInventory() {
        assertThrows(IllegalStateException.class, () -> sales.checkout(
                new SaleDraft(partId, 4, 2500, 0, null, "CASH", null)));

        assertEquals(3, parts.findById(partId).quantity());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sales", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM payments", Integer.class));
    }

    @Test
    void discountCannotExceedSubtotalAndDoesNotPostSale() {
        assertThrows(IllegalArgumentException.class, () -> sales.checkout(
                new SaleDraft(partId, 1, 2500, 2501, null, "CASH", null)));

        assertEquals(3, parts.findById(partId).quantity());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sales", Integer.class));
    }
}
