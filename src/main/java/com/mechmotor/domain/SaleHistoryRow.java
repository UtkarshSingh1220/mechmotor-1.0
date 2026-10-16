package com.mechmotor.domain;

/** Immutable read model for the recent-sales workspace; monetary values are stored in paise. */
public record SaleHistoryRow(
        long id,
        String saleNumber,
        String customerName,
        String createdAt,
        long subtotalPaise,
        long discountPaise,
        long totalPaise,
        String status,
        String paymentMethods,
        int units,
        long refundedPaise) { }
