package com.mechmotor.domain;

public record SaleReturnable(long saleId, String saleNumber, String customerName, long saleItemId, long partId,
                             String sku, String partName, int soldQuantity, int returnedQuantity,
                             int remainingQuantity, long lineNetTotalPaise, long refundedPaise,
                             long remainingRefundPaise) { }
