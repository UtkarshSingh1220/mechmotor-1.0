package com.mechmotor.domain;

public record PurchaseOrderReceipt(long purchaseId, String purchaseNumber, String supplierName,
                                   int lineCount, int units, long totalPaise) { }
