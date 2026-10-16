package com.mechmotor.domain;

public record PurchaseCancellationReceipt(
        String purchaseNumber,
        String reason,
        int lineCount,
        int unitsReversed,
        long totalPaise) { }
