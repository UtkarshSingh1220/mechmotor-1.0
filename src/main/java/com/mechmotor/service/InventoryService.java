package com.mechmotor.service;

import com.mechmotor.domain.InventoryFilter;
import com.mechmotor.domain.InventoryPage;
import com.mechmotor.domain.LookupOption;
import com.mechmotor.domain.Part;
import com.mechmotor.domain.PartDraft;
import com.mechmotor.repository.PartRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class InventoryService {
    private final PartRepository repository;

    public InventoryService(PartRepository repository) { this.repository = repository; }

    public List<Part> search(String query) { return repository.search(query); }

    public InventoryPage search(InventoryFilter filter) { return repository.findPage(filter); }

    public Part get(long id) { return repository.findById(id); }

    public List<LookupOption> categories() { return repository.categories(); }

    public List<LookupOption> suppliers() { return repository.suppliers(); }

    @Transactional
    public long create(PartDraft draft) {
        validateReferences(draft);
        return repository.create(draft);
    }

    @Transactional
    public void update(long id, PartDraft draft) {
        validateReferences(draft);
        repository.update(id, draft);
    }

    @Transactional
    public void deactivate(long id) { repository.deactivate(id); }

    private void validateReferences(PartDraft draft) {
        if (draft.categoryId() != null && repository.categories().stream().noneMatch(o -> o.id() == draft.categoryId())) {
            throw new IllegalArgumentException("Selected category is not active or does not exist.");
        }
        if (draft.supplierId() != null && repository.suppliers().stream().noneMatch(o -> o.id() == draft.supplierId())) {
            throw new IllegalArgumentException("Selected supplier does not exist.");
        }
    }
}
