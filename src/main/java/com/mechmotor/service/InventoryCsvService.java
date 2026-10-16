package com.mechmotor.service;

import com.mechmotor.domain.InventoryFilter;
import com.mechmotor.domain.InventoryPage;
import com.mechmotor.domain.Part;
import com.mechmotor.domain.PartDraft;
import com.mechmotor.domain.LookupOption;
import com.mechmotor.repository.PartRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PushbackReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.List;

/** Streams the complete filtered active inventory to an Excel-friendly UTF-8 CSV file. */
@RequiresFeature("CSV")
@Service
public class InventoryCsvService {
    private static final int EXPORT_PAGE_SIZE = 200;
    private static final String[] HEADER = {
            "SKU", "Barcode", "Part Number", "Name", "Brand", "Company", "Material",
            "Color", "Category", "Supplier", "Quantity", "Reorder Level", "Purchase Price INR", "Selling Price INR"
    };

    private static final long MAX_IMPORT_BYTES = 25L * 1024 * 1024;
    private static final int MAX_IMPORT_ROWS = 20_000;
    private static final List<String> IMPORT_COLUMNS = List.of(
            "sku", "barcode", "part number", "name", "brand", "company", "material", "color",
            "category", "supplier", "quantity", "reorder level", "purchase price inr", "selling price inr");

    private final PartRepository repository;
    private final TransactionTemplate transactions;

    public InventoryCsvService(PartRepository repository, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public long export(Path requestedPath, InventoryFilter requestedFilter) throws IOException {
        if (requestedPath == null || requestedPath.getFileName() == null) {
            throw new IllegalArgumentException("Choose a destination CSV file.");
        }
        Path destination = requestedPath.toAbsolutePath().normalize();
        if (!destination.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw new IllegalArgumentException("The export filename must end in .csv.");
        }
        InventoryFilter filter = new InventoryFilter(
                requestedFilter.query(), requestedFilter.brand(), requestedFilter.material(), requestedFilter.color(),
                requestedFilter.categoryId(), requestedFilter.supplierId(), requestedFilter.stockStatus(),
                requestedFilter.minQuantity(), requestedFilter.maxQuantity(),
                requestedFilter.minSellingPricePaise(), requestedFilter.maxSellingPricePaise(),
                0, EXPORT_PAGE_SIZE, requestedFilter.sortBy(), requestedFilter.ascending());

        Path parent = destination.getParent();
        if (parent != null) Files.createDirectories(parent);
        long exported = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(destination, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            // UTF-8 BOM helps spreadsheet software correctly detect Unicode text.
            writer.write('\uFEFF');
            writeRow(writer, HEADER);
            int pageNumber = 0;
            while (true) {
                InventoryFilter pageFilter = new InventoryFilter(
                        filter.query(), filter.brand(), filter.material(), filter.color(),
                        filter.categoryId(), filter.supplierId(), filter.stockStatus(),
                        filter.minQuantity(), filter.maxQuantity(), filter.minSellingPricePaise(),
                        filter.maxSellingPricePaise(), pageNumber, EXPORT_PAGE_SIZE,
                        filter.sortBy(), filter.ascending());
                InventoryPage page = repository.findPage(pageFilter);
                for (Part part : page.items()) {
                    writeRow(writer, new String[]{
                            part.sku(), part.barcode(), part.partNumber(), part.name(), part.brand(),
                            part.company(), part.material(), part.color(), part.category(), part.supplier(),
                            Integer.toString(part.quantity()), Integer.toString(part.reorderLevel()),
                            BigDecimal.valueOf(part.purchasePricePaise(), 2).toPlainString(),
                            BigDecimal.valueOf(part.sellingPricePaise(), 2).toPlainString()
                    });
                    exported++;
                }
                if (page.items().isEmpty() || pageNumber + 1 >= page.totalPages()) break;
                pageNumber++;
            }
            writer.flush();
        }
        return exported;
    }

    /**
     * Imports inventory rows atomically. The CSV must contain the columns produced
     * by export(); existing SKUs are never updated, and any invalid row rolls back
     * the entire batch. Vehicle fitment associations are managed separately.
     */
    public long importFrom(Path requestedPath) throws IOException {
        if (requestedPath == null || !Files.isRegularFile(requestedPath)) {
            throw new IllegalArgumentException("Choose an existing CSV file.");
        }
        Path source = requestedPath.toAbsolutePath().normalize();
        if (!source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw new IllegalArgumentException("The import filename must end in .csv.");
        }
        if (Files.size(source) > MAX_IMPORT_BYTES) {
            throw new IllegalArgumentException("CSV import is limited to 25 MB per file.");
        }

        List<List<String>> rows = readRows(source);
        if (rows.size() < 2) throw new IllegalArgumentException("CSV must include a header and at least one inventory row.");
        Map<String, Integer> columns = new HashMap<>();
        List<String> header = rows.get(0);
        for (int i = 0; i < header.size(); i++) {
            String name = header.get(i).strip().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) throw new IllegalArgumentException("CSV contains an empty column name.");
            if (columns.putIfAbsent(name, i) != null) throw new IllegalArgumentException("CSV contains duplicate column: " + name);
        }
        for (String required : IMPORT_COLUMNS) {
            if (!columns.containsKey(required)) throw new IllegalArgumentException("CSV is missing required column: " + required);
        }

        List<LookupOption> categories = repository.categories();
        List<LookupOption> suppliers = repository.suppliers();
        List<PartDraft> drafts = new ArrayList<>();
        Set<String> seenSkus = new HashSet<>();
        for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
            List<String> row = rows.get(rowIndex);
            if (row.stream().allMatch(value -> value == null || value.isBlank())) continue;
            if (row.size() != header.size()) {
                throw new IllegalArgumentException("CSV row " + (rowIndex + 1) + " has " + row.size()
                        + " fields; expected " + header.size() + ".");
            }
            String sku = cell(row, columns, "sku");
            String normalizedSku = sku.strip().toLowerCase(Locale.ROOT);
            if (!seenSkus.add(normalizedSku)) {
                throw new IllegalArgumentException("Duplicate SKU in CSV at row " + (rowIndex + 1) + ": " + sku);
            }
            Long categoryId = resolveLookup(cell(row, columns, "category"), categories, "category");
            Long supplierId = resolveLookup(cell(row, columns, "supplier"), suppliers, "supplier");
            PartDraft draft;
            try {
                draft = new PartDraft(sku, cell(row, columns, "barcode"), cell(row, columns, "part number"),
                        cell(row, columns, "name"), cell(row, columns, "brand"), cell(row, columns, "company"),
                        cell(row, columns, "material"), cell(row, columns, "color"), categoryId, supplierId,
                        normalizePrice(cell(row, columns, "purchase price inr"), rowIndex + 1, "purchase price"),
                        normalizePrice(cell(row, columns, "selling price inr"), rowIndex + 1, "selling price"),
                        parseNonNegativeInt(cell(row, columns, "quantity"), rowIndex + 1, "quantity"),
                        parseNonNegativeInt(cell(row, columns, "reorder level"), rowIndex + 1, "reorder level"));
                draft.purchasePricePaise();
                draft.sellingPricePaise();
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("CSV row " + (rowIndex + 1) + ": " + ex.getMessage(), ex);
            }
            drafts.add(draft);
            if (drafts.size() > MAX_IMPORT_ROWS) throw new IllegalArgumentException("CSV import is limited to 20,000 inventory rows.");
        }
        if (drafts.isEmpty()) throw new IllegalArgumentException("CSV contains no inventory rows to import.");

        return transactions.execute(status -> {
            for (int i = 0; i < drafts.size(); i++) {
                try {
                    repository.create(drafts.get(i));
                } catch (RuntimeException ex) {
                    throw new IllegalStateException("Import rolled back at SKU '" + drafts.get(i).sku()
                            + "' (row " + (i + 2) + "). No rows from this import were retained.", ex);
                }
            }
            return (long) drafts.size();
        });
    }

    private static List<List<String>> readRows(Path source) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (PushbackReader reader = new PushbackReader(Files.newBufferedReader(source, StandardCharsets.UTF_8), 1)) {
            List<String> row = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean quoted = false;
            boolean firstCharacter = true;
            int current;
            while ((current = reader.read()) != -1) {
                char ch = (char) current;
                if (firstCharacter) {
                    firstCharacter = false;
                    if (ch == '\uFEFF') continue;
                }
                if (quoted) {
                    if (ch == '"') {
                        int next = reader.read();
                        if (next == '"') field.append('"');
                        else {
                            quoted = false;
                            if (next != -1) reader.unread(next);
                        }
                    } else {
                        field.append(ch);
                    }
                    continue;
                }
                if (ch == '"' && field.length() == 0) {
                    quoted = true;
                } else if (ch == '"') {
                    throw new IllegalArgumentException("Malformed CSV: quote inside an unquoted field.");
                } else if (ch == ',') {
                    row.add(field.toString());
                    field.setLength(0);
                } else if (ch == '\r' || ch == '\n') {
                    if (ch == '\r') {
                        int next = reader.read();
                        if (next != '\n' && next != -1) reader.unread(next);
                    }
                    row.add(field.toString());
                    field.setLength(0);
                    if (row.stream().anyMatch(value -> value != null && !value.isBlank())) rows.add(new ArrayList<>(row));
                    row.clear();
                    if (rows.size() > MAX_IMPORT_ROWS + 1) {
                        throw new IllegalArgumentException("CSV import is limited to 20,000 inventory rows.");
                    }
                } else {
                    field.append(ch);
                }
            }
            if (quoted) throw new IllegalArgumentException("Malformed CSV: unterminated quoted field.");
            if (field.length() > 0 || !row.isEmpty()) {
                row.add(field.toString());
                if (row.stream().anyMatch(value -> value != null && !value.isBlank())) rows.add(new ArrayList<>(row));
            }
        }
        return rows;
    }

    private static String cell(List<String> row, Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        return index == null || index >= row.size() ? "" : row.get(index);
    }

    private static Long resolveLookup(String rawName, List<LookupOption> options, String label) {
        if (rawName == null || rawName.isBlank()) return null;
        String requested = rawName.strip();
        List<LookupOption> matches = options.stream().filter(option ->
                option.name().equalsIgnoreCase(requested) || option.name().endsWith(" / " + requested)).toList();
        if (matches.isEmpty()) throw new IllegalArgumentException("Unknown " + label + " '" + requested + "'. Create it before importing.");
        if (matches.size() > 1) throw new IllegalArgumentException("Ambiguous " + label + " '" + requested + "'. Use a unique category or supplier name.");
        return matches.get(0).id();
    }

    private static int parseNonNegativeInt(String raw, int row, String label) {
        try {
            int value = Integer.parseInt(raw.strip());
            if (value < 0) throw new IllegalArgumentException(label + " cannot be negative.");
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("CSV row " + row + ": " + label + " must be a whole number.");
        }
    }

    private static String normalizePrice(String raw, int row, String label) {
        if (raw == null || raw.isBlank()) return "0.00";
        try {
            BigDecimal value = new BigDecimal(raw.strip()).setScale(2, RoundingMode.UNNECESSARY);
            if (value.signum() < 0) throw new IllegalArgumentException(label + " cannot be negative.");
            value.movePointRight(2).longValueExact();
            return value.toPlainString();
        } catch (NumberFormatException | ArithmeticException ex) {
            throw new IllegalArgumentException("CSV row " + row + ": " + label + " must be non-negative with at most two decimal places.");
        }
    }

    private static boolean looksLikeFormula(String value) {
        int index = 0;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) index++;
        if (index >= value.length()) return false;
        char first = value.charAt(index);
        return first == '=' || first == '+' || first == '-' || first == '@';
    }

    private static void writeRow(BufferedWriter writer, String[] values) throws IOException {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) writer.write(',');
            String value = values[i] == null ? "" : values[i];
            // Prevent spreadsheet formula execution from user-controlled text cells.
            if (looksLikeFormula(value)) value = "'" + value;
            boolean quote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                    || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
            if (quote) writer.write('"');
            for (int j = 0; j < value.length(); j++) {
                char ch = value.charAt(j);
                if (ch == '"') writer.write("\"\"");
                else writer.write(ch);
            }
            if (quote) writer.write('"');
        }
        writer.write("\r\n");
    }
}
