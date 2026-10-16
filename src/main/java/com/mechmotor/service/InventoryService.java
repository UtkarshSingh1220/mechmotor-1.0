package com.mechmotor.service;

import com.mechmotor.domain.InventoryFilter;
import com.mechmotor.domain.InventoryDashboardMetrics;
import com.mechmotor.domain.InventoryPage;
import com.mechmotor.domain.LookupOption;
import com.mechmotor.domain.Part;
import com.mechmotor.domain.PartDraft;
import com.mechmotor.domain.PartDetails;
import com.mechmotor.domain.SupplierDetails;
import com.mechmotor.domain.SupplierDraft;
import com.mechmotor.domain.VehicleFitment;
import com.mechmotor.domain.VehicleFitmentDraft;
import com.mechmotor.domain.SavedInventoryView;
import com.mechmotor.repository.PartRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class InventoryService {
    private final PartRepository repository;

    public InventoryService(PartRepository repository) { this.repository = repository; }

    public InventoryDashboardMetrics dashboardMetrics() { return repository.dashboardMetrics(); }

    public List<Part> search(String query) { return repository.search(query); }

    public InventoryPage search(InventoryFilter filter) { return repository.findPage(filter); }

    public Part get(long id) { return repository.findById(id); }

    public PartDetails getDetails(long id) { return repository.findDetailsById(id); }

    public List<LookupOption> categories() { return repository.categories(); }

    public List<LookupOption> suppliers() { return repository.suppliers(); }

    public List<LookupOption> savedViews() { return repository.savedViews(); }

    public SavedInventoryView getSavedView(long id) { return repository.savedView(id); }

    @Transactional
    public long saveView(String name, InventoryFilter filter) { return repository.saveView(name, filter); }

    @Transactional
    public void deleteSavedView(long id) { repository.deleteSavedView(id); }

    public List<VehicleFitment> fitments(long partId) { return repository.fitments(partId); }

    @Transactional
    public long addFitment(long partId, VehicleFitmentDraft draft) { return repository.addFitment(partId, draft); }

    @Transactional
    public void removeFitment(long partId, long fitmentId) { repository.removeFitment(partId, fitmentId); }

    @Transactional
    public long createCategory(String name) { return repository.createCategory(name); }

    @Transactional
    public long createCategory(String name, Long parentId) { return repository.createCategory(name, parentId); }

    @Transactional
    public void renameCategory(long id, String name) { repository.renameCategory(id, name); }

    @Transactional
    public void deactivateCategory(long id) { repository.deactivateCategory(id); }

    @Transactional
    public long createSupplier(String name) { return repository.createSupplier(name); }

    @Transactional
    public long createSupplier(SupplierDraft draft) { return repository.createSupplier(draft); }

    public List<SupplierDetails> supplierDetails() { return repository.supplierDetails(); }

    public SupplierDetails getSupplier(long id) { return repository.supplierDetails(id); }

    @Transactional
    public void updateSupplier(long id, SupplierDraft draft) { repository.updateSupplier(id, draft); }

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
