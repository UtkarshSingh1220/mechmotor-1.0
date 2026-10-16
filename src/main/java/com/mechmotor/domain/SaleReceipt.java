package com.mechmotor.domain;

public record SaleReceipt(
        long saleId,
        String saleNumber,
        String sku,
        String partName,
        int quantity,
        long subtotalPaise,
        long discountPaise,
        long totalPaise,
        String paymentMethod) { }
