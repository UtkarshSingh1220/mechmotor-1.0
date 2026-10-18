package com.mechmotor.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaxCalculationServiceTest {
    private final TaxCalculationService calculator = new TaxCalculationService();

    @Test
    void addsTaxToDiscountedBaseUsingHalfUpPaiseRounding() {
        var result = calculator.calculate(10001, 1,
                profile("06", 1800, true), "06");
        assertEquals(10000, result.taxableBasePaise());
        assertEquals(1800, result.taxAmountPaise());
        assertEquals(900, result.cgstPaise());
        assertEquals(900, result.sgstPaise());
        assertEquals(11800, result.totalPaise());
        assertEquals("LOCAL", result.classification());
    }

    @Test
    void usesIgstForKnownInterstateBuyer() {
        var result = calculator.calculate(10000, 0, profile("06", 500, true), "07");
        assertEquals(500, result.igstPaise());
        assertEquals(0, result.cgstPaise());
        assertEquals(0, result.sgstPaise());
        assertEquals("INTERSTATE", result.classification());
    }

    @Test
    void marksMissingBuyerStateAsProvisionalAndNeverSilentlyClaimsConfirmedLocalSupply() {
        var result = calculator.calculate(10000, 0, profile("06", 500, true), null);
        assertEquals("PROVISIONAL_LOCAL", result.classification());
        assertEquals("", result.buyerStateCode());
        assertEquals(250, result.cgstPaise());
        assertEquals(250, result.sgstPaise());
    }

    @Test
    void unclassifiesSupplyWhenSellerStateIsMissing() {
        var result = calculator.calculate(10000, 0, profile("", 500, true), "07");
        assertEquals("UNCLASSIFIED", result.classification());
        assertEquals(500, result.taxAmountPaise());
        assertEquals(0, result.cgstPaise() + result.sgstPaise() + result.igstPaise());
    }

    @Test
    void disabledTaxDoesNotAddOrSnapshotAnActiveRate() {
        var result = calculator.calculate(10000, 500, profile("06", 1800, false), "06");
        assertEquals(9500, result.totalPaise());
        assertEquals(0, result.taxRateBps());
        assertEquals(0, result.taxAmountPaise());
        assertEquals("NONE", result.classification());
    }

    @Test
    void rejectsMalformedStateCodesAndInvalidDiscounts() {
        assertThrows(IllegalArgumentException.class,
                () -> calculator.calculate(10000, 0, profile("06", 500, true), "Haryana"));
        assertThrows(IllegalArgumentException.class,
                () -> calculator.calculate(10000, 10001, profile("06", 500, true), "06"));
    }

    private static ShopProfileService.ShopProfile profile(String state, int rate, boolean enabled) {
        return new ShopProfileService.ShopProfile("Test Shop", "", "", "", "", state, "GST", rate, enabled);
    }
}
