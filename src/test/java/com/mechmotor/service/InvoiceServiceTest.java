package com.mechmotor.service;

import com.mechmotor.domain.PartDraft;
import com.mechmotor.domain.SaleDraft;
import com.mechmotor.domain.SaleReturnDraft;
import com.mechmotor.domain.SaleReturnable;
import com.mechmotor.domain.SaleReceipt;
import com.mechmotor.repository.PartRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.*;

class InvoiceServiceTest {
    @TempDir Path tempDir;
    private JdbcTemplate jdbc;
    private PartRepository parts;
    private SalesService sales;
    private InvoiceService invoices;
    private long brakePart;
    private long filterPart;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource source = new SQLiteDataSource(config);
        source.setUrl("jdbc:sqlite:" + tempDir.resolve("invoice-test.db").toAbsolutePath());
        try (Connection connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new org.springframework.core.io.ClassPathResource("schema.sql"));
        }
        jdbc = new JdbcTemplate(source);
        parts = new PartRepository(jdbc);
        sales = new SalesService(jdbc, parts, new DataSourceTransactionManager(source));
        invoices = new InvoiceService(jdbc);
        brakePart = parts.create(new PartDraft("INV-BRK-1", null, null, "Brake pad",
                "Brand", "Company", "Ceramic", "Black", null, null, "10.00", "25.00", 5, 2));
        filterPart = parts.create(new PartDraft("INV-FLT-1", null, null, "Oil filter",
                "Brand", "Company", "Paper", "White", null, null, "5.00", "12.00", 4, 1));
    }

    @Test
    void invoiceContainsCartSnapshotsPaymentAndPartialReturnAudit() throws Exception {
        var sale = sales.checkout(new com.mechmotor.domain.CartSaleDraft(java.util.List.of(
                new com.mechmotor.domain.SaleLineDraft(brakePart, 2, 2500),
                new com.mechmotor.domain.SaleLineDraft(filterPart, 1, 1200)),
                200, "Walk-in customer", "UPI", "UPI-123"));
        SaleReturnable line = sales.returnableSale(sale.saleNumber()).stream()
                .filter(item -> item.partId() == brakePart).findFirst().orElseThrow();
        sales.returnSale(new SaleReturnDraft(sale.saleNumber(), line.saleItemId(), 1,
                "UPI", "REFUND-123", "Wrong part"));

        Path output = tempDir.resolve("invoice.pdf");
        assertEquals(output, invoices.exportSaleInvoice(sale.saleNumber(), output));
        try (PDDocument document = Loader.loadPDF(output.toFile())) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains(sale.saleNumber()));
            assertTrue(text.contains("Brake pad"));
            assertTrue(text.contains("Oil filter"));
            assertTrue(text.contains("UPI"));
            assertTrue(text.contains("RETURNS RECORDED: 1 unit(s)"));
            assertTrue(text.contains("Refunds recorded"));
        }
    }

    @Test
    void voidedInvoiceShowsVoidAndRefundAudit() throws Exception {
        SaleReceipt sale = sales.checkout(new SaleDraft(brakePart, 1, 2500, 0,
                "Walk-in", "CASH", null));
        sales.voidSale(sale.saleNumber(), "Customer cancelled", "CASH-REFUND-1");

        Path output = tempDir.resolve("voided-invoice.pdf");
        invoices.exportSaleInvoice(sale.saleNumber(), output);
        try (PDDocument document = Loader.loadPDF(output.toFile())) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("VOIDED"));
            assertTrue(text.contains("Customer cancelled"));
            assertTrue(text.contains("Refund recorded"));
        }
    }

    @Test
    void invoiceIncludesSavedShopProfileAndTransparentTaxConfiguration() throws Exception {
        new ShopProfileService(jdbc).save(new ShopProfileService.ShopProfile(
                "North Road Spares", "12 Market Road, Gurugram", "+91 9876543210",
                "sales@example.com", "22AAAAA0000A1Z5", "06", "GST", 1800, true));
        SaleReceipt sale = sales.checkout(new SaleDraft(brakePart, 1, 2500, 0,
                "Walk-in", "CASH", null));
        Path output = tempDir.resolve("profile-invoice.pdf");
        invoices.exportSaleInvoice(sale.saleNumber(), output);
        try (PDDocument document = Loader.loadPDF(output.toFile())) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("North Road Spares"));
            assertTrue(text.contains("12 Market Road, Gurugram"));
            assertTrue(text.contains("22AAAAA0000A1Z5"));
            assertTrue(text.contains("GST 18.00%"));
            assertTrue(text.contains("no tax amount is calculated"));
        }
    }

    @Test
    void refusesToOverwriteAnExistingInvoice() throws Exception {
        SaleReceipt sale = sales.checkout(new SaleDraft(brakePart, 1, 2500, 0,
                null, "CASH", null));
        Path output = tempDir.resolve("existing.pdf");
        Files.writeString(output, "keep existing file");
        assertThrows(java.nio.file.FileAlreadyExistsException.class,
                () -> invoices.exportSaleInvoice(sale.saleNumber(), output));
        assertEquals("keep existing file", Files.readString(output));
    }
}
