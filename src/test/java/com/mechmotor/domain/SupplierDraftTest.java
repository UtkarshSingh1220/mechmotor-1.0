package com.mechmotor.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SupplierDraftTest {
    @Test
    void rejectsMissingOrOversizedSupplierName() {
        assertThrows(IllegalArgumentException.class, () -> new SupplierDraft(" ", null, null).validate());
        assertThrows(IllegalArgumentException.class,
                () -> new SupplierDraft("N".repeat(161), null, null).validate());
    }

    @Test
    void rejectsOversizedPhoneAndSupplierCode() {
        assertThrows(IllegalArgumentException.class,
                () -> new SupplierDraft("Supplier", "1".repeat(41), null).validate());
        assertThrows(IllegalArgumentException.class,
                () -> new SupplierDraft("Supplier", null, "C".repeat(81)).validate());
    }

    @Test
    void acceptsOptionalContactFields() {
        assertDoesNotThrow(() -> new SupplierDraft("Supplier", null, null).validate());
    }
}
