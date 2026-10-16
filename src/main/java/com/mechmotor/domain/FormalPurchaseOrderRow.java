package com.mechmotor.domain;

public record FormalPurchaseOrderRow(long id, String orderNumber, String supplierName, String status,
                                     String createdAt, String expectedDate, String notes,
                                     String itemSummary, int lineCount, int units, long totalPaise,
                                     String receiptNumber, String cancellationReason) { }
