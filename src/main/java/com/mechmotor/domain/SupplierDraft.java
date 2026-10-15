package com.mechmotor.domain;

public record SupplierDraft(String name, String phone, String supplierCode) {
    public void validate() {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Supplier name is required.");
        if (name.strip().length() > 160) throw new IllegalArgumentException("Supplier name cannot exceed 160 characters.");
        if (phone != null && phone.strip().length() > 40) throw new IllegalArgumentException("Supplier phone cannot exceed 40 characters.");
        if (supplierCode != null && supplierCode.strip().length() > 80) throw new IllegalArgumentException("Supplier code cannot exceed 80 characters.");
    }
}
