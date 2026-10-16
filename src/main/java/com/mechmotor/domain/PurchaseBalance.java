package com.mechmotor.domain;

public record PurchaseBalance(String purchaseNumber,String supplierName,String status,long totalPaise,
                              long paidPaise,long outstandingPaise) { }
