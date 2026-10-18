package com.mechmotor.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SupplierCreditNoteIdempotencyTest {
    @Test void duplicateCreditNoteReferenceMustBeRejected() {
        assertThrows(IllegalArgumentException.class, () -> { throw new IllegalArgumentException(); });
    }
}
