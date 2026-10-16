package com.mechmotor.domain;

public record SupplierReturnableLine(long purchaseItemId, long partId, String sku, String name,
                                     int receivedQuantity, int previouslyReturnedQuantity,
                                     int returnableQuantity, int availableQuantity, long unitCostPaise) { }
