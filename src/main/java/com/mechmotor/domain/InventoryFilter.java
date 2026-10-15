package com.mechmotor.domain;

/** Validated, bounded inventory query options. Null/blank fields are ignored. */
public record InventoryFilter(
        String query, String brand, String material, String color,
        Long categoryId, Long supplierId, StockStatus stockStatus,
        Integer minQuantity, Integer maxQuantity,
        Long minSellingPricePaise, Long maxSellingPricePaise,
        int page, int pageSize, String sortBy, boolean ascending) {
    public enum StockStatus { ANY, IN_STOCK, LOW_STOCK, OUT_OF_STOCK }

    public InventoryFilter {
        query = clean(query);
        brand = clean(brand);
        material = clean(material);
        color = clean(color);
        stockStatus = stockStatus == null ? StockStatus.ANY : stockStatus;
        if (minQuantity != null && minQuantity < 0) throw new IllegalArgumentException("Minimum quantity cannot be negative.");
        if (maxQuantity != null && maxQuantity < 0) throw new IllegalArgumentException("Maximum quantity cannot be negative.");
        if (minQuantity != null && maxQuantity != null && minQuantity > maxQuantity)
            throw new IllegalArgumentException("Minimum quantity cannot exceed maximum quantity.");
        if (minSellingPricePaise != null && minSellingPricePaise < 0) throw new IllegalArgumentException("Minimum price cannot be negative.");
        if (maxSellingPricePaise != null && maxSellingPricePaise < 0) throw new IllegalArgumentException("Maximum price cannot be negative.");
        if (minSellingPricePaise != null && maxSellingPricePaise != null && minSellingPricePaise > maxSellingPricePaise)
            throw new IllegalArgumentException("Minimum price cannot exceed maximum price.");
        page = Math.max(0, page);
        pageSize = Math.max(1, Math.min(pageSize, 200));
        String requestedSort = sortBy == null ? "name" : sortBy;
        sortBy = switch (requestedSort) {
            case "sku", "name", "brand", "quantity", "sellingPrice", "updatedAt" -> requestedSort;
            default -> "name";
        };
    }

    public static InventoryFilter defaults(String query) {
        return new InventoryFilter(query, null, null, null, null, null, StockStatus.ANY,
                null, null, null, null, 0, 50, "name", true);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip().replaceAll("\\s+", " ");
    }
}
