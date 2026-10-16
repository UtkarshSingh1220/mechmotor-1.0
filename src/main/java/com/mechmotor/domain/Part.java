package com.mechmotor.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record Part(long id, String sku, String barcode, String partNumber, String name,
                   String brand, String company, String material, String color,
                   String category, String supplier, int quantity, long purchasePricePaise,
                   long sellingPricePaise, int reorderLevel) {
    public Part(long id, String sku, String barcode, String partNumber, String name,
                String brand, String company, String material, String color,
                String category, String supplier, int quantity, long sellingPricePaise) {
        this(id, sku, barcode, partNumber, name, brand, company, material, color,
                category, supplier, quantity, 0L, sellingPricePaise, 0);
    }
    public String displayValue(String property) {
        return switch (property) {
            case "sku" -> safe(sku);
            case "name" -> safe(name);
            case "brand" -> safe(brand) + (company == null || company.isBlank() ? "" : " / " + company);
            case "material" -> safe(material);
            case "color" -> safe(color);
            case "category" -> safe(category);
            case "quantity" -> Integer.toString(quantity);
            case "sellingPrice" -> "₹" + BigDecimal.valueOf(sellingPricePaise, 2)
                    .setScale(2, RoundingMode.HALF_UP).toPlainString();
            default -> "";
        };
    }
    private static String safe(String value) { return value == null ? "" : value; }
}
