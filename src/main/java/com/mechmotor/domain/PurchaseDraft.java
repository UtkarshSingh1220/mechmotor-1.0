package com.mechmotor.domain;

public record PurchaseDraft(
        long partId,
        int quantity,
        long unitCostPaise,
        long supplierId,
        String supplierInvoiceReference) {
    public PurchaseDraft {
        if (partId <= 0) throw new IllegalArgumentException("Select an inventory item.");
        if (quantity <= 0 || quantity > 100_000) throw new IllegalArgumentException("Quantity must be between 1 and 100,000.");
        if (unitCostPaise < 0) throw new IllegalArgumentException("Unit cost cannot be negative.");
        if (supplierId <= 0) throw new IllegalArgumentException("Choose a supplier.");
        supplierInvoiceReference = supplierInvoiceReference == null || supplierInvoiceReference.isBlank()
                ? null : supplierInvoiceReference.strip().replaceAll("\\s+", " ");
        if (supplierInvoiceReference != null && supplierInvoiceReference.length() > 120) {
            throw new IllegalArgumentException("Supplier invoice reference cannot exceed 120 characters.");
        }
    }
}
