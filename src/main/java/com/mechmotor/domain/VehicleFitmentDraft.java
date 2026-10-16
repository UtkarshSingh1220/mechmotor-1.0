package com.mechmotor.domain;

public record VehicleFitmentDraft(
        String make, String model, int yearFrom, int yearTo,
        String engine, String trim, String notes) {
    public VehicleFitmentDraft {
        make = cleanRequired(make, "Vehicle make", 100);
        model = cleanRequired(model, "Vehicle model", 100);
        engine = cleanOptional(engine, 100, "Engine");
        trim = cleanOptional(trim, 100, "Trim");
        notes = cleanOptional(notes, 500, "Fitment notes");
        if (yearFrom < 1886 || yearFrom > 2100 || yearTo < 1886 || yearTo > 2100) {
            throw new IllegalArgumentException("Model years must be between 1886 and 2100.");
        }
        if (yearFrom > yearTo) throw new IllegalArgumentException("Start year cannot exceed end year.");
    }

    private static String cleanRequired(String raw, String label, int max) {
        String value = cleanOptional(raw, max, label);
        if (value == null) throw new IllegalArgumentException(label + " is required.");
        return value;
    }

    private static String cleanOptional(String raw, int max, String label) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.strip().replaceAll("\\s+", " ");
        if (value.length() > max) throw new IllegalArgumentException(label + " cannot exceed " + max + " characters.");
        return value;
    }
}
