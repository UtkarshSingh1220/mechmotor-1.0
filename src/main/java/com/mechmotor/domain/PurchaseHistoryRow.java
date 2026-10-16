package com.mechmotor.domain;

/** Read-only purchase history row; money values are in paise. */
public record PurchaseHistoryRow(
        long id,
        String purchaseNumber,
        String supplierName,
        String supplierInvoiceReference,
        String createdAt,
        long totalPaise,
        String status,
        int units,
        String itemSummary,
        String cancellationReason,
        long paidPaise,
        long outstandingPaise) { }
