package com.mechmotor.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/** Generates a printable sale invoice/receipt from immutable sale snapshots and audit records. */
@RequiresFeature("SALES")
@Service
public class InvoiceService {
    private static final int LINES_PER_PAGE = 44;
    private final JdbcTemplate jdbc;
    private final ShopProfileService shopProfileService;

    /** Compatibility constructor for isolated invoice tests using a JdbcTemplate. */
    public InvoiceService(JdbcTemplate jdbc) {
        this(jdbc, new ShopProfileService(jdbc));
    }

    @Autowired
    public InvoiceService(JdbcTemplate jdbc, ShopProfileService shopProfileService) {
        this.jdbc = jdbc;
        this.shopProfileService = shopProfileService;
    }

    public Path exportSaleInvoice(String rawSaleNumber, Path requestedTarget) throws Exception {
        String saleNumber = rawSaleNumber == null ? "" : rawSaleNumber.strip();
        if (saleNumber.isEmpty() || saleNumber.length() > 64) {
            throw new IllegalArgumentException("Enter a valid sale number.");
        }
        SaleHeader sale = jdbc.query("""
            SELECT id,sale_number,customer_name,subtotal_paise,discount_paise,total_paise,status,created_at
            FROM sales WHERE sale_number=? COLLATE NOCASE
            """, rs -> rs.next() ? new SaleHeader(rs.getLong("id"), rs.getString("sale_number"),
                rs.getString("customer_name"), rs.getLong("subtotal_paise"), rs.getLong("discount_paise"),
                rs.getLong("total_paise"), rs.getString("status"), rs.getString("created_at")) : null, saleNumber);
        if (sale == null) throw new IllegalArgumentException("No sale found with that sale number.");

        List<SaleLine> items = jdbc.query("""
            SELECT sku_snapshot,name_snapshot,quantity,unit_price_paise,line_total_paise
            FROM sale_items WHERE sale_id=? ORDER BY id
            """, (rs, row) -> new SaleLine(rs.getString("sku_snapshot"), rs.getString("name_snapshot"),
                rs.getInt("quantity"), rs.getLong("unit_price_paise"), rs.getLong("line_total_paise")), sale.id());
        if (items.isEmpty()) throw new IllegalStateException("Sale has no item snapshots; invoice cannot be generated.");

        List<PaymentLine> payments = jdbc.query("""
            SELECT method,amount_paise,reference FROM payments WHERE sale_id=? ORDER BY id
            """, (rs, row) -> new PaymentLine(rs.getString("method"), rs.getLong("amount_paise"),
                rs.getString("reference")), sale.id());
        ReturnTotals returns = jdbc.query("""
            SELECT COALESCE(SUM(quantity),0) quantity,COALESCE(SUM(refund_paise),0) refund
            FROM sale_returns WHERE sale_id=?
            """, rs -> rs.next() ? new ReturnTotals(rs.getInt("quantity"), rs.getLong("refund")) : new ReturnTotals(0,0), sale.id());
        VoidDetails voidDetails = jdbc.query("""
            SELECT reason,amount_refunded_paise,refund_method,refund_reference
            FROM sale_voids WHERE sale_id=?
            """, rs -> rs.next() ? new VoidDetails(rs.getString("reason"), rs.getLong("amount_refunded_paise"),
                rs.getString("refund_method"), rs.getString("refund_reference")) : null, sale.id());

        ShopProfileService.ShopProfile shop = shopProfileService.load();
        List<InvoiceLine> lines = new ArrayList<>();
        lines.add(new InvoiceLine(shop.shopName(), true, 18));
        if (!shop.address().isBlank()) lines.add(new InvoiceLine("Address: " + shop.address(), false, 9));
        if (!shop.phone().isBlank()) lines.add(new InvoiceLine("Phone: " + shop.phone(), false, 9));
        if (!shop.email().isBlank()) lines.add(new InvoiceLine("Email: " + shop.email(), false, 9));
        if (!shop.gstin().isBlank()) lines.add(new InvoiceLine("GSTIN: " + shop.gstin(), false, 9));
        if (!shop.stateCode().isBlank()) lines.add(new InvoiceLine("State code: " + shop.stateCode(), false, 9));
        lines.add(new InvoiceLine("MECHMOTOR 1.0 - VEHICLE PARTS", false, 9));
        lines.add(new InvoiceLine("SALES INVOICE / RECEIPT", true, 14));
        lines.add(new InvoiceLine("Invoice number: " + sale.saleNumber(), true, 11));
        lines.add(new InvoiceLine("Sale date (local database time): " + sale.createdAt(), false, 10));
        lines.add(new InvoiceLine("Customer: " + (sale.customerName() == null ? "Walk-in customer" : sale.customerName()), false, 10));
        lines.add(new InvoiceLine("Status: " + sale.status(), true, 11));
        if ("VOIDED".equals(sale.status())) {
            lines.add(new InvoiceLine("VOIDED - verify the actual refund separately with the payment provider/cashier.", true, 10));
            if (voidDetails != null) {
                lines.add(new InvoiceLine("Void reason: " + voidDetails.reason(), false, 9));
                lines.add(new InvoiceLine("Refund recorded: " + money(voidDetails.amountRefundedPaise())
                        + " | original payment method: " + safeNullable(voidDetails.refundMethod())
                        + " | refund reference: " + safeNullable(voidDetails.refundReference()), false, 9));
            }
        }
        lines.add(new InvoiceLine("", false, 8));
        lines.add(new InvoiceLine("ITEMS", true, 11));
        lines.add(new InvoiceLine("SKU | DESCRIPTION | QTY x UNIT PRICE | LINE TOTAL", true, 9));
        lines.add(new InvoiceLine("--------------------------------------------------------------------------", false, 9));
        for (SaleLine item : items) {
            lines.add(new InvoiceLine(item.sku() + " | " + item.name(), true, 9));
            lines.add(new InvoiceLine("Qty " + item.quantity() + " x " + money(item.unitPricePaise())
                    + " = " + money(item.lineTotalPaise()), false, 9));
        }
        lines.add(new InvoiceLine("--------------------------------------------------------------------------", false, 9));
        lines.add(new InvoiceLine("Subtotal: " + money(sale.subtotalPaise()), false, 10));
        lines.add(new InvoiceLine("Discount: -" + money(sale.discountPaise()), false, 10));
        if (shop.taxEnabled()) {
            lines.add(new InvoiceLine("Tax profile: " + shop.taxLabel() + " " + shop.formattedTaxRate()
                    + (shop.gstin().isBlank() ? "" : " | GSTIN " + shop.gstin()), false, 9));
            lines.add(new InvoiceLine("Tax is configured as a default only; no tax amount is calculated or added to this saved sale.", false, 8));
        }
        lines.add(new InvoiceLine("NET SALE TOTAL: " + money(sale.totalPaise()), true, 12));
        if (!payments.isEmpty()) {
            lines.add(new InvoiceLine("PAYMENT", true, 10));
            for (PaymentLine payment : payments) {
                lines.add(new InvoiceLine(payment.method() + ": " + money(payment.amountPaise())
                        + " | reference: " + safeNullable(payment.reference()), false, 9));
            }
        }
        if (returns.quantity() > 0) {
            lines.add(new InvoiceLine("RETURNS RECORDED: " + returns.quantity() + " unit(s)", true, 10));
            lines.add(new InvoiceLine("Refunds recorded: " + money(returns.refundPaise())
                    + " | remaining net sale value: " + money(Math.max(0, sale.totalPaise() - returns.refundPaise())), false, 9));
        }
        lines.add(new InvoiceLine("", false, 8));
        lines.add(new InvoiceLine("Generated locally by MechMotor. Keep this receipt for your records.", false, 8));
        lines.add(new InvoiceLine("Refunds are audit records; this PDF does not prove external payment settlement.", false, 8));

        lines = wrapLines(lines, 100);

        Path target = requestedTarget.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) throw new IOException("Choose a valid destination folder.");
        Files.createDirectories(parent);
        if (Files.exists(target)) throw new java.nio.file.FileAlreadyExistsException(target.toString());
        Path temporary = Files.createTempFile(parent, ".mechmotor-invoice-", ".tmp");
        try {
            try (PDDocument document = new PDDocument()) {
                int offset = 0;
                while (offset < lines.size()) {
                    PDPage page = new PDPage();
                    document.addPage(page);
                    int end = offset;
                    float y = 790;
                    while (end < lines.size() && y - lines.get(end).size() - 7 >= 48) {
                        y -= lines.get(end).size() + 7;
                        end++;
                    }
                    if (end == offset) end++;
                    try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                        y = 790;
                        for (int i = offset; i < end; i++) {
                            InvoiceLine line = lines.get(i);
                            PDType1Font font = new PDType1Font(line.bold()
                                    ? Standard14Fonts.FontName.HELVETICA_BOLD
                                    : Standard14Fonts.FontName.HELVETICA);
                            stream.beginText();
                            stream.setFont(font, line.size());
                            stream.newLineAtOffset(48, y);
                            stream.showText(ascii(line.text()));
                            stream.endText();
                            y -= line.size() + 7;
                        }
                        stream.beginText();
                        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 8);
                        stream.newLineAtOffset(48, 28);
                        stream.showText("Page " + (document.getNumberOfPages()) + " | MechMotor 1.0");
                        stream.endText();
                    }
                    offset = end;
                }
                document.save(temporary.toFile());
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, target);
            }
            return target;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }


    /** Wraps long business/customer/item fields instead of silently truncating invoice content. */
    private static List<InvoiceLine> wrapLines(List<InvoiceLine> source, int maxChars) {
        List<InvoiceLine> wrapped = new ArrayList<>();
        for (InvoiceLine line : source) {
            String text = ascii(line.text());
            if (text.isEmpty()) {
                wrapped.add(new InvoiceLine("", line.bold(), line.size()));
                continue;
            }
            StringBuilder current = new StringBuilder();
            for (String word : text.split("\\s+")) {
                if (current.length() > 0 && current.length() + 1 + word.length() > maxChars) {
                    wrapped.add(new InvoiceLine(current.toString(), line.bold(), line.size()));
                    current.setLength(0);
                }
                while (word.length() > maxChars) {
                    if (current.length() > 0) {
                        wrapped.add(new InvoiceLine(current.toString(), line.bold(), line.size()));
                        current.setLength(0);
                    }
                    wrapped.add(new InvoiceLine(word.substring(0, maxChars), line.bold(), line.size()));
                    word = word.substring(maxChars);
                }
                if (current.length() > 0) current.append(' ');
                current.append(word);
            }
            if (current.length() > 0) wrapped.add(new InvoiceLine(current.toString(), line.bold(), line.size()));
        }
        return wrapped;
    }

    private static String money(long paise) {
        return "INR " + BigDecimal.valueOf(paise, 2).setScale(2).toPlainString();
    }

    private static String safeNullable(String value) {
        return value == null || value.isBlank() ? "not provided" : value;
    }

    private static String ascii(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKD);
        String ascii = normalized.replaceAll("[^\\x20-\\x7E]", "?");
        return ascii;
    }

    private record SaleHeader(long id, String saleNumber, String customerName, long subtotalPaise,
                              long discountPaise, long totalPaise, String status, String createdAt) { }
    private record SaleLine(String sku, String name, int quantity, long unitPricePaise, long lineTotalPaise) { }
    private record PaymentLine(String method, long amountPaise, String reference) { }
    private record ReturnTotals(int quantity, long refundPaise) { }
    private record VoidDetails(String reason, long amountRefundedPaise, String refundMethod, String refundReference) { }
    private record InvoiceLine(String text, boolean bold, int size) { }
}
