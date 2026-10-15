package com.mechmotor.service;

import com.mechmotor.domain.Part;
import com.mechmotor.repository.PartRepository;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class InventoryService {
    private final PartRepository repository;
    public InventoryService(PartRepository repository) { this.repository = repository; }
    public List<Part> search(String query) { return repository.search(query); }
}
