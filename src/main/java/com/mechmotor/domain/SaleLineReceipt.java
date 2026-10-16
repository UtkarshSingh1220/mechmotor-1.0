package com.mechmotor.domain;

public record SaleLineReceipt(
        long partId, String sku, String partName, int quantity,
        long unitPricePaise, long lineTotalPaise) { }
