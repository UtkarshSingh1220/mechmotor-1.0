package com.mechmotor.domain;

public record SaleReturnReceipt(String returnNumber, String saleNumber, String sku, String partName,
                                int quantity, long refundPaise, String refundMethod) { }
