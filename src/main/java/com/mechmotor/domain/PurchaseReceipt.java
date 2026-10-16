package com.mechmotor.domain;

public record PurchaseReceipt(
        long purchaseId,
        String purchaseNumber,
        String sku,
        String partName,
        String supplierName,
        int quantity,
        long unitCostPaise,
        long totalPaise) { }
