package com.mechmotor;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceAdministrationTest {
    @Test void sendsToDefaultWhenNoEmailEntered() {
        assertEquals(List.of("default@example.com"),
                WorkspaceAdministration.recipients("default@example.com","",false));
    }
    @Test void sendsOnlyToEnteredAddressByDefault() {
        assertEquals(List.of("other@example.com"),
                WorkspaceAdministration.recipients("default@example.com","other@example.com",false));
    }
    @Test void sendsToBothWhenCheckboxSelected() {
        assertEquals(List.of("other@example.com","default@example.com"),
                WorkspaceAdministration.recipients("default@example.com","other@example.com",true));
    }
    @Test void doesNotDuplicateSameAddress() {
        assertEquals(List.of("same@example.com"),
                WorkspaceAdministration.recipients("same@example.com","same@example.com",true));
    }
    @Test void requiresDefaultWhenRequestedForBoth() {
        assertThrows(IllegalArgumentException.class,
                ()->WorkspaceAdministration.recipients("","other@example.com",true));
    }
    @Test void rejectsInvalidEmail() {
        assertThrows(IllegalArgumentException.class,
                ()->WorkspaceAdministration.recipients("","bad@@email",false));
    }
    @Test void requiresRecipient() {
        assertThrows(IllegalArgumentException.class,
                ()->WorkspaceAdministration.recipients("","",false));
    }
    @Test void defaultsToTodayForBothDateBounds() {
        LocalDate today=LocalDate.now();
        assertTrue(WorkspaceAdministration.validReportDates(today,today));
    }
    @Test void rejectsReversedOrMissingDates() {
        LocalDate today=LocalDate.now();
        assertFalse(WorkspaceAdministration.validReportDates(today,today.minusDays(1)));
        assertFalse(WorkspaceAdministration.validReportDates(null,today));
        assertFalse(WorkspaceAdministration.validReportDates(today,null));
    }
    @Test void restrictsReportDateRange() {
        LocalDate start=LocalDate.of(2026,1,1);
        assertTrue(WorkspaceAdministration.validReportDates(start,start.plusDays(366)));
        assertFalse(WorkspaceAdministration.validReportDates(start,start.plusDays(367)));
    }

}
