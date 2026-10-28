package com.mechmotor.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceUserServiceValidationTest {
    @Test void acceptsNormalEmail() {
        assertDoesNotThrow(() -> WorkspaceUserService.requireEmail("reports@example.com"));
    }
    @Test void rejectsEmptyEmail() {
        assertThrows(IllegalArgumentException.class,
                () -> WorkspaceUserService.requireEmail(""));
    }
    @Test void rejectsMalformedEmail() {
        assertThrows(IllegalArgumentException.class,
                () -> WorkspaceUserService.requireEmail("not-an-email"));
    }
}
