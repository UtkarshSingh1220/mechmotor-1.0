package com.mechmotor.domain;

public record SaleReturnable(long saleId, String saleNumber, String customerName, long partId,
                             String sku, String partName, int soldQuantity, int returnedQuantity,
                             int remainingQuantity, long saleTotalPaise, long refundedPaise,
                             long remainingRefundPaise) { }
