package com.mechmotor.service;

import com.mechmotor.domain.InventoryFilter;
import com.mechmotor.domain.InventoryPage;
import com.mechmotor.domain.Part;
import com.mechmotor.repository.PartRepository;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/** Streams the complete filtered active inventory to an Excel-friendly UTF-8 CSV file. */
@Service
public class InventoryCsvService {
    private static final int EXPORT_PAGE_SIZE = 200;
    private static final String[] HEADER = {
            "SKU", "Barcode", "Part Number", "Name", "Brand", "Company", "Material",
            "Color", "Category", "Supplier", "Quantity", "Selling Price INR"
    };

    private final PartRepository repository;

    public InventoryCsvService(PartRepository repository) {
        this.repository = repository;
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
                            Integer.toString(part.quantity()), java.math.BigDecimal.valueOf(part.sellingPricePaise(), 2).toPlainString()
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
