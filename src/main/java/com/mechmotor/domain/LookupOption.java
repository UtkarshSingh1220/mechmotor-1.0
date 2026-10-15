package com.mechmotor.domain;

public record LookupOption(long id, String name) {
    @Override public String toString() { return name; }
}
