package com.mechmotor.domain;

public record InventoryDashboardMetrics(
        long activeParts,
        long totalUnits,
        long lowStockParts,
        long outOfStockParts,
        long retailStockValuePaise) { }
