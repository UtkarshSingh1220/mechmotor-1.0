package com.mechmotor.domain;

public record PurchaseLineDraft(long partId, int quantity, long unitCostPaise) {
    public PurchaseLineDraft {
        if (partId <= 0) throw new IllegalArgumentException("Select an inventory item.");
        if (quantity <= 0 || quantity > 100_000) throw new IllegalArgumentException("Quantity must be between 1 and 100,000.");
        if (unitCostPaise < 0) throw new IllegalArgumentException("Unit cost cannot be negative.");
    }
}
