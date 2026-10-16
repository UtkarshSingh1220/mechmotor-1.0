package com.mechmotor.domain;

public record SaleReturnDraft(String saleNumber, long saleItemId, int quantity, String refundMethod,
                              String refundReference, String reason) {
    public SaleReturnDraft {
        if (saleNumber == null || saleNumber.isBlank()) throw new IllegalArgumentException("Enter a sale number.");
        saleNumber = saleNumber.strip().toUpperCase(java.util.Locale.ROOT);
        if (saleNumber.length() > 64) throw new IllegalArgumentException("Sale number cannot exceed 64 characters.");
        if (saleItemId <= 0) throw new IllegalArgumentException("Select a sale item line.");
        if (quantity <= 0 || quantity > 100_000) throw new IllegalArgumentException("Return quantity must be between 1 and 100,000.");
        refundMethod = refundMethod == null ? "" : refundMethod.strip().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("CASH", "UPI", "CARD", "BANK_TRANSFER").contains(refundMethod)) {
            throw new IllegalArgumentException("Choose a supported refund method.");
        }
        refundReference = cleanOptional(refundReference, 120, "Refund reference");
        reason = cleanOptional(reason, 250, "Return reason");
    }
    private static String cleanOptional(String raw, int max, String label) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.strip().replaceAll("\\s+", " ");
        if (value.length() > max) throw new IllegalArgumentException(label + " cannot exceed " + max + " characters.");
        return value;
    }
}
