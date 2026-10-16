package com.mechmotor.domain;

public record FormalPurchaseOrderLineRow(long id, long partId, String sku, String name,
                                         int orderedQuantity, int receivedQuantity,
                                         int remainingQuantity, long unitCostPaise) { }
