package com.mechmotor.domain;

public record SaleVoidReceipt(
        long saleId,
        String saleNumber,
        int lineCount,
        int unitsRestored,
        long amountRefundedPaise,
        String refundMethod,
        String reason) { }
