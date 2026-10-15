package com.mechmotor.service;

import com.mechmotor.domain.LookupOption;
import com.mechmotor.domain.PartDraft;
import com.mechmotor.repository.PartRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InventoryServiceTest {
    @Test
    void rejectsUnknownCategoryBeforeCreatingPart() {
        PartRepository repository = mock(PartRepository.class);
        when(repository.categories()).thenReturn(List.of(new LookupOption(1L, "Brakes")));
        when(repository.suppliers()).thenReturn(List.of());
        InventoryService service = new InventoryService(repository);

        PartDraft draft = draft(99L, null);

        assertThrows(IllegalArgumentException.class, () -> service.create(draft));
    }

    @Test
    void rejectsUnknownSupplierBeforeCreatingPart() {
        PartRepository repository = mock(PartRepository.class);
        when(repository.categories()).thenReturn(List.of());
        when(repository.suppliers()).thenReturn(List.of(new LookupOption(1L, "North Supply")));
        InventoryService service = new InventoryService(repository);

        PartDraft draft = draft(null, 99L);

        assertThrows(IllegalArgumentException.class, () -> service.create(draft));
    }

    private static PartDraft draft(Long categoryId, Long supplierId) {
        return new PartDraft("TEST-1", null, null, "Test part", null, null, null, null,
                categoryId, supplierId, "0.00", "0.00", 0, 0);
    }
}
