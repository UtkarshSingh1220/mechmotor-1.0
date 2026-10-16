package com.mechmotor.domain;

public record SupplierPaymentReceipt(long paymentId,String purchaseNumber,String method,long amountPaise,
                                     long paidTotalPaise,long outstandingPaise,String reference) { }
