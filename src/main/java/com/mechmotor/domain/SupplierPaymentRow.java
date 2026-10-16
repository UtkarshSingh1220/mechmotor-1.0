package com.mechmotor.domain;

public record SupplierPaymentRow(long id,String purchaseNumber,String supplierName,String method,long amountPaise,
                                 String reference,String status,String voidReason,String createdAt) { }
