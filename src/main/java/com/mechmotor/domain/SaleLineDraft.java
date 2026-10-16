package com.mechmotor.domain;

public record SaleLineDraft(long partId, int quantity, long unitPricePaise) {
    public SaleLineDraft {
        if (partId <= 0) throw new IllegalArgumentException("Select an inventory item.");
        if (quantity <= 0 || quantity > 100_000) throw new IllegalArgumentException("Quantity must be between 1 and 100,000.");
        if (unitPricePaise < 0) throw new IllegalArgumentException("Unit price cannot be negative.");
    }
}
