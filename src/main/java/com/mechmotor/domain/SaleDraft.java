package com.mechmotor.domain;

public record SaleDraft(
        long partId,
        int quantity,
        long unitPricePaise,
        long discountPaise,
        String customerName,
        String paymentMethod,
        String paymentReference) {
    public SaleDraft {
        if (partId <= 0) throw new IllegalArgumentException("Select an inventory item.");
        if (quantity <= 0 || quantity > 100_000) throw new IllegalArgumentException("Quantity must be between 1 and 100,000.");
        if (unitPricePaise < 0) throw new IllegalArgumentException("Unit price cannot be negative.");
        if (discountPaise < 0) throw new IllegalArgumentException("Discount cannot be negative.");
        customerName = cleanOptional(customerName, 160, "Customer name");
        paymentMethod = paymentMethod == null ? "" : paymentMethod.strip().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("CASH", "UPI", "CARD", "BANK_TRANSFER").contains(paymentMethod)) {
            throw new IllegalArgumentException("Choose a supported payment method.");
        }
        paymentReference = cleanOptional(paymentReference, 120, "Payment reference");
    }

    private static String cleanOptional(String raw, int max, String label) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.strip().replaceAll("\\s+", " ");
        if (value.length() > max) throw new IllegalArgumentException(label + " cannot exceed " + max + " characters.");
        return value;
    }
}
