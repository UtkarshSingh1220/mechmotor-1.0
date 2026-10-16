package com.mechmotor.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/** Bounded-date operational CSV exports for sales, supplier receipts, and stock ledger activity. */
@RequiresFeature("CSV")
@Service
public class OperationalReportsService {
    private static final int MAX_RANGE_DAYS = 366;
    private final JdbcTemplate jdbc;

    public OperationalReportsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long export(ReportType type, Path requestedPath, LocalDate from, LocalDate through) throws IOException {
        if (type == null) throw new IllegalArgumentException("Choose a report type.");
        validateRange(from, through);
        Path destination = validateDestination(requestedPath);
        Path parent = destination.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, ".mechmotor-report-", ".tmp");
        boolean published = false;
        try {
            long rows;
            try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                writer.write('\uFEFF');
                rows = switch (type) {
                    case SALES -> writeSales(writer, from, through);
                    case PURCHASES -> writePurchases(writer, from, through);
                    case STOCK_MOVEMENTS -> writeStockMovements(writer, from, through);
                };
            }
            // Do not request ATOMIC_MOVE: providers may replace an existing target under
            // that option. A plain move without REPLACE_EXISTING preserves no-overwrite semantics.
            Files.move(temp, destination);
            published = true;
            return rows;
        } finally {
            if (!published) Files.deleteIfExists(temp);
        }
    }

    private long writeSales(BufferedWriter writer, LocalDate from, LocalDate through) throws IOException {
        row(writer, "Sale number", "Created at", "Customer", "Status", "Subtotal INR", "Discount INR", "Total INR", "Recorded payment INR");
        List<String[]> rows = jdbc.query("""
            SELECT s.sale_number,s.created_at,COALESCE(s.customer_name,''),s.status,
                   s.subtotal_paise,s.discount_paise,s.total_paise,
                   COALESCE((SELECT SUM(p.amount_paise) FROM payments p WHERE p.sale_id=s.id),0) AS paid
            FROM sales s
            WHERE date(s.created_at) >= ? AND date(s.created_at) <= ?
            ORDER BY s.created_at,s.id
            """, (rs, n) -> new String[]{
                rs.getString("sale_number"),rs.getString("created_at"),rs.getString(3),rs.getString("status"),
                rupees(rs.getLong("subtotal_paise")),rupees(rs.getLong("discount_paise")),
                rupees(rs.getLong("total_paise")),rupees(rs.getLong("paid"))
            }, from.toString(), through.toString());
        for (String[] values : rows) row(writer, values);
        return rows.size();
    }

    private long writePurchases(BufferedWriter writer, LocalDate from, LocalDate through) throws IOException {
        row(writer, "Purchase number", "Created at", "Supplier", "Supplier invoice reference", "Status", "Units", "Total INR");
        List<String[]> rows = jdbc.query("""
            SELECT p.purchase_number,p.created_at,p.supplier_name_snapshot,
                   COALESCE(p.supplier_invoice_reference,''),p.status,p.subtotal_paise,
                   COALESCE((SELECT SUM(pi.quantity) FROM purchase_items pi WHERE pi.purchase_id=p.id),0) AS units
            FROM purchases p
            WHERE date(p.created_at) >= ? AND date(p.created_at) <= ?
            ORDER BY p.created_at,p.id
            """, (rs, n) -> new String[]{
                rs.getString("purchase_number"),rs.getString("created_at"),rs.getString("supplier_name_snapshot"),
                rs.getString(4),rs.getString("status"),Integer.toString(rs.getInt("units")),
                rupees(rs.getLong("subtotal_paise"))
            }, from.toString(), through.toString());
        for (String[] values : rows) row(writer, values);
        return rows.size();
    }

    private long writeStockMovements(BufferedWriter writer, LocalDate from, LocalDate through) throws IOException {
        row(writer, "Movement ID", "Created at", "SKU", "Part name", "Movement type", "Quantity", "Reference", "Reason");
        List<String[]> rows = jdbc.query("""
            SELECT m.id,m.created_at,p.sku,p.name,m.movement_type,m.quantity,
                   COALESCE(m.reference,''),COALESCE(m.reason,'')
            FROM stock_movements m JOIN parts p ON p.id=m.part_id
            WHERE date(m.created_at) >= ? AND date(m.created_at) <= ?
            ORDER BY m.created_at,m.id
            """, (rs, n) -> new String[]{
                Long.toString(rs.getLong("id")),rs.getString("created_at"),rs.getString("sku"),
                rs.getString("name"),rs.getString("movement_type"),Integer.toString(rs.getInt("quantity")),
                rs.getString(7),rs.getString(8)
            }, from.toString(), through.toString());
        for (String[] values : rows) row(writer, values);
        return rows.size();
    }

    private static void validateRange(LocalDate from, LocalDate through) {
        if (from == null || through == null) throw new IllegalArgumentException("Choose both report dates.");
        if (through.isBefore(from)) throw new IllegalArgumentException("End date cannot be before start date.");
        if (through.toEpochDay() - from.toEpochDay() >= MAX_RANGE_DAYS)
            throw new IllegalArgumentException("Report range cannot exceed 366 days.");
    }

    private static Path validateDestination(Path requested) {
        if (requested == null || requested.getFileName() == null)
            throw new IllegalArgumentException("Choose a report destination.");
        Path destination = requested.toAbsolutePath().normalize();
        if (!destination.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv"))
            throw new IllegalArgumentException("Report filename must end in .csv.");
        if (Files.exists(destination)) throw new IllegalArgumentException("The destination already exists. Choose a new filename.");
        return destination;
    }

    private static String rupees(long paise) {
        return java.math.BigDecimal.valueOf(paise, 2).toPlainString();
    }

    private static void row(BufferedWriter writer, String... values) throws IOException {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) writer.write(',');
            String value = values[i] == null ? "" : values[i];
            // Neutralize spreadsheet formulas without turning signed numeric quantities into text.
            boolean signedNumber = value.matches("-?\d+(\.\d+)?");
            if (!value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0 && !signedNumber) value = "'" + value;
            writer.write('"');
            writer.write(value.replace("\"", "\"\""));
            writer.write('"');
        }
        writer.newLine();
    }

    public enum ReportType { SALES, PURCHASES, STOCK_MOVEMENTS }
}
