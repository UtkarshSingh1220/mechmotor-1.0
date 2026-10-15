package com.mechmotor.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PartDraftTest {
    @Test
    void rejectsMissingRequiredFieldsAndNegativeQuantity() {
        assertThrows(IllegalArgumentException.class, () -> draft(" ", "Part", 0, "1.00"));
        assertThrows(IllegalArgumentException.class, () -> draft("SKU-1", " ", 0, "1.00"));
        assertThrows(IllegalArgumentException.class, () -> draft("SKU-1", "Part", -1, "1.00"));
    }

    @Test
    void rejectsNegativeAndOverPrecisionPrices() {
        PartDraft negative = draft("SKU-1", "Part", 0, "-1.00");
        assertThrows(IllegalArgumentException.class, negative::sellingPricePaise);

        PartDraft overPrecision = draft("SKU-2", "Part", 0, "1.001");
        assertThrows(IllegalArgumentException.class, overPrecision::sellingPricePaise);
    }

    @Test
    void convertsRupeesToIntegerPaise() {
        PartDraft draft = new PartDraft("SKU-3", null, null, "Part", null, null, null, null,
                null, null, "12.34", "56.78", 4, 1);

        assertEquals(1234L, draft.purchasePricePaise());
        assertEquals(5678L, draft.sellingPricePaise());
    }

    private static PartDraft draft(String sku, String name, int quantity, String price) {
        return new PartDraft(sku, null, null, name, null, null, null, null,
                null, null, "0.00", price, quantity, 0);
    }
}
