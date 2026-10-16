package com.mechmotor.domain;

public record PurchaseOrderReceiveLine(long partId, int quantity) {
    public PurchaseOrderReceiveLine {
        if (partId <= 0) throw new IllegalArgumentException("Choose a valid purchase order part.");
        if (quantity <= 0) throw new IllegalArgumentException("Received quantity must be greater than zero.");
    }
}
