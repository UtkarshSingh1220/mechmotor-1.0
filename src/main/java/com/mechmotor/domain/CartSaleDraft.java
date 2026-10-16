package com.mechmotor.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record CartSaleDraft(
        List<SaleLineDraft> lines,
        long discountPaise,
        String customerName,
        String paymentMethod,
        String paymentReference) {
    public CartSaleDraft {
        lines = lines == null ? List.of() : List.copyOf(lines);
        if (lines.isEmpty()) throw new IllegalArgumentException("Add at least one item to the sale.");
        if (lines.size() > 100) throw new IllegalArgumentException("A sale cannot contain more than 100 distinct items.");
        Set<Long> partIds = new HashSet<>();
        for (SaleLineDraft line : lines) {
            if (line == null) throw new IllegalArgumentException("Sale lines cannot be empty.");
            if (!partIds.add(line.partId())) throw new IllegalArgumentException("Each part can appear only once in the cart; update its quantity instead.");
        }
        if (discountPaise < 0) throw new IllegalArgumentException("Discount cannot be negative.");
        customerName = cleanOptional(customerName, 160, "Customer name");
        paymentMethod = paymentMethod == null ? "" : paymentMethod.strip().toUpperCase(Locale.ROOT);
        if (!Set.of("CASH", "UPI", "CARD", "BANK_TRANSFER").contains(paymentMethod)) {
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
