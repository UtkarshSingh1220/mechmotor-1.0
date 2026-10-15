package com.mechmotor.domain;

import java.util.List;

public record InventoryPage(List<Part> items, long totalItems, int page, int pageSize) {
    public InventoryPage { items = List.copyOf(items); }
    public int totalPages() { return (int) Math.max(1, (totalItems + pageSize - 1) / pageSize); }
}
