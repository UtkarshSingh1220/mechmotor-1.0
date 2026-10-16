package com.mechmotor.domain;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;

public record FormalPurchaseOrderDraft(long supplierId, String expectedDate, String notes,
                                       List<PurchaseLineDraft> lines) {
    public FormalPurchaseOrderDraft {
        if (supplierId <= 0) throw new IllegalArgumentException("Choose a supplier.");
        expectedDate = expectedDate == null || expectedDate.isBlank() ? null : expectedDate.strip();
        if (expectedDate != null) {
            try { LocalDate.parse(expectedDate); }
            catch (DateTimeParseException ex) { throw new IllegalArgumentException("Expected date must use YYYY-MM-DD.", ex); }
        }
        notes = notes == null || notes.isBlank() ? null : notes.strip().replaceAll("\\s+", " ");
        if (notes != null && notes.length() > 500) throw new IllegalArgumentException("Purchase order notes cannot exceed 500 characters.");
        if (lines == null || lines.isEmpty()) throw new IllegalArgumentException("Add at least one purchase order line.");
        if (lines.size() > 100) throw new IllegalArgumentException("A purchase order can contain at most 100 distinct parts.");
        if (lines.stream().anyMatch(java.util.Objects::isNull)) throw new IllegalArgumentException("Purchase order lines cannot be null.");
        lines = List.copyOf(lines);
        HashSet<Long> partIds = new HashSet<>();
        for (PurchaseLineDraft line : lines) {
            if (!partIds.add(line.partId())) throw new IllegalArgumentException("Each part can appear only once per purchase order.");
        }
    }
}
