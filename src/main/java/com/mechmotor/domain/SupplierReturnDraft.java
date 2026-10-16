package com.mechmotor.domain;

import java.util.HashSet;
import java.util.List;

public record SupplierReturnDraft(String purchaseNumber, String reason, String creditNoteReference,
                                  List<SupplierReturnLineDraft> lines) {
    public SupplierReturnDraft {
        purchaseNumber = purchaseNumber == null ? "" : purchaseNumber.strip();
        reason = reason == null ? "" : reason.strip().replaceAll("\\s+", " ");
        creditNoteReference = creditNoteReference == null ? null : creditNoteReference.strip();
        if (purchaseNumber.isEmpty() || purchaseNumber.length() > 64)
            throw new IllegalArgumentException("Enter a valid supplier receipt number.");
        if (reason.isEmpty() || reason.length() > 240)
            throw new IllegalArgumentException("Return reason must contain 1–240 characters.");
        if (creditNoteReference != null && creditNoteReference.isEmpty()) creditNoteReference = null;
        if (creditNoteReference != null && creditNoteReference.length() > 120)
            throw new IllegalArgumentException("Credit note reference cannot exceed 120 characters.");
        lines = lines == null ? List.of() : List.copyOf(lines);
        if (lines.isEmpty() || lines.size() > 100)
            throw new IllegalArgumentException("Choose between 1 and 100 received lines to return.");
        if (lines.stream().anyMatch(java.util.Objects::isNull))
            throw new IllegalArgumentException("Return lines cannot be null.");
        if (new HashSet<>(lines.stream().map(SupplierReturnLineDraft::purchaseItemId).toList()).size() != lines.size())
            throw new IllegalArgumentException("A received purchase line can appear only once per return.");
    }
}
