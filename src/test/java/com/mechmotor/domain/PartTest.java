package com.mechmotor.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PartTest {
    @Test void formatsRupeesFromPaiseWithoutFloatingPoint() {
        Part part = new Part(1, "SKU-1", null, null, "Brake Pad", "Bosch", null,
                "Ceramic", "Black", "Brakes", null, 3, 12345);
        assertEquals("₹123.45", part.displayValue("sellingPrice"));
    }

    @Test void safelyDisplaysNullText() {
        Part part = new Part(1, "SKU-1", null, null, "Brake Pad", null, null,
                null, null, null, null, 0, 0);
        assertEquals("", part.displayValue("material"));
        assertEquals("", part.displayValue("brand"));
    }
}
