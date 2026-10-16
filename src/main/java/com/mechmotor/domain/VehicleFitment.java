package com.mechmotor.domain;

public record VehicleFitment(
        long id, long partId, String make, String model, int yearFrom, int yearTo,
        String engine, String trim, String notes) {
    @Override public String toString() {
        StringBuilder value = new StringBuilder(make).append(" ").append(model)
                .append(" (").append(yearFrom).append("–").append(yearTo).append(")");
        if (engine != null && !engine.isBlank()) value.append(" • ").append(engine);
        if (trim != null && !trim.isBlank()) value.append(" • ").append(trim);
        return value.toString();
    }
}
