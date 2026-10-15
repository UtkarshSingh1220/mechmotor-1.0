package com.mechmotor.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/** User-editable part fields; prices are represented as decimal rupees at the UI boundary. */
public record PartDraft(String sku, String barcode, String partNumber, String name,
                        String brand, String company, String material, String color,
                        Long categoryId, Long supplierId, String purchasePriceRupees,
                        String sellingPriceRupees, int quantity, int reorderLevel) {
    public PartDraft {
        sku = required(sku, "SKU");
        name = required(name, "Part name");
        barcode = clean(barcode);
        partNumber = clean(partNumber);
        brand = clean(brand);
        company = clean(company);
        material = clean(material);
        color = clean(color);
        if (quantity < 0) throw new IllegalArgumentException("Quantity cannot be negative.");
        if (reorderLevel < 0) throw new IllegalArgumentException("Reorder level cannot be negative.");
        if (quantity < reorderLevel && reorderLevel == 0) { /* zero is a valid disabled threshold */ }
    }

    public long purchasePricePaise() { return paise(purchasePriceRupees, "Purchase price"); }
    public long sellingPricePaise() { return paise(sellingPriceRupees, "Selling price"); }

    private static long paise(String value, String label) {
        if (value == null || value.isBlank()) return 0;
        try {
            BigDecimal amount = new BigDecimal(value.strip()).setScale(2, RoundingMode.UNNECESSARY);
            if (amount.signum() < 0) throw new IllegalArgumentException(label + " cannot be negative.");
            return amount.movePointRight(2).longValueExact();
        } catch (NumberFormatException | ArithmeticException ex) {
            throw new IllegalArgumentException(label + " must be a non-negative amount with at most two decimal places.");
        }
    }

    private static String required(String value, String label) {
        String clean = clean(value);
        if (clean == null) throw new IllegalArgumentException(label + " is required.");
        if (clean.length() > 160) throw new IllegalArgumentException(label + " cannot exceed 160 characters.");
        return clean;
    }
    private static String clean(String value) {
        if (value == null || value.isBlank()) return null;
        return value.strip().replaceAll("\\s+", " ");
    }
}
