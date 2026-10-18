package com.mechmotor.domain;

public record SupplierReturnReceipt(String returnNumber, String purchaseNumber, String supplierName,
                                    String reason, String creditNoteReference, int lineCount,
                                    int unitsReturned, long totalPaise, long outstandingPaise) { }
