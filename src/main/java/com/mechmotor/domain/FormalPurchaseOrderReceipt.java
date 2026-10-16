package com.mechmotor.domain;

public record FormalPurchaseOrderReceipt(String orderNumber, String receiptNumber,
                                         int lineCount, int units, long totalPaise) { }
