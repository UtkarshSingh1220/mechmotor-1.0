package com.mechmotor.domain;

public record SupplierReturnRow(String returnNumber, String purchaseNumber, String supplierName,
                                String reason, String creditNoteReference, String createdAt,
                                int lineCount, int unitsReturned, long totalPaise) { }
