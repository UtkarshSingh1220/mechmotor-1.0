package com.mechmotor.domain;

public record SupplierReturnLineDraft(long purchaseItemId, int quantity) {
    public SupplierReturnLineDraft {
        if (purchaseItemId <= 0) throw new IllegalArgumentException("Choose a valid received purchase line.");
        if (quantity <= 0) throw new IllegalArgumentException("Return quantity must be greater than zero.");
    }
}
