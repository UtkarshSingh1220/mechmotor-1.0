package com.mechmotor.domain;

import java.util.HashSet;
import java.util.List;

public record PurchaseOrderDraft(long supplierId, String supplierInvoiceReference, List<PurchaseLineDraft> lines) {
    public PurchaseOrderDraft {
        if (supplierId <= 0) throw new IllegalArgumentException("Choose a supplier.");
        supplierInvoiceReference = supplierInvoiceReference == null || supplierInvoiceReference.isBlank()
                ? null : supplierInvoiceReference.strip().replaceAll("\\s+", " ");
        if (supplierInvoiceReference != null && supplierInvoiceReference.length() > 120)
            throw new IllegalArgumentException("Supplier invoice reference cannot exceed 120 characters.");
        if (lines == null || lines.isEmpty()) throw new IllegalArgumentException("Add at least one purchase line.");
        if (lines.size() > 100) throw new IllegalArgumentException("A receipt can contain at most 100 distinct parts.");
        if (lines.stream().anyMatch(java.util.Objects::isNull)) throw new IllegalArgumentException("Purchase lines cannot be null.");
        lines = List.copyOf(lines);
        HashSet<Long> partIds = new HashSet<>();
        for (PurchaseLineDraft line : lines) {
            if (!partIds.add(line.partId())) throw new IllegalArgumentException("Each part can appear only once per receipt.");
        }
    }
}
