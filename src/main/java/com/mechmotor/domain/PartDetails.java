package com.mechmotor.domain;

public record PartDetails(long id, String sku, String barcode, String partNumber, String name,
                          String brand, String company, String material, String color,
                          Long categoryId, Long supplierId, long purchasePricePaise,
                          long sellingPricePaise, int quantity, int reorderLevel) { }
