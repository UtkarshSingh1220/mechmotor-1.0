package com.mechmotor.service;

import java.math.BigInteger;
import java.util.Locale;

/**
 * Applies a configured tax-exclusive rate to the discounted taxable base.
 * State-based GST component selection is provisional when buyer state is omitted.
 */
public final class TaxCalculationService {
    public TaxSnapshot calculate(long subtotalPaise, long discountPaise,
                                 ShopProfileService.ShopProfile profile, String rawBuyerStateCode) {
        if (subtotalPaise < 0 || discountPaise < 0 || discountPaise > subtotalPaise) {
            throw new IllegalArgumentException("Taxable subtotal and discount are invalid.");
        }
        if (profile == null) throw new IllegalArgumentException("Shop tax profile is required.");
        long base = subtotalPaise - discountPaise;
        String sellerState = normalizeState(profile.stateCode(), "Seller");
        String buyerState = normalizeState(rawBuyerStateCode, "Buyer");
        if (!profile.taxEnabled() || profile.defaultTaxRateBps() == 0) {
            return new TaxSnapshot(base, profile.taxEnabled() ? profile.defaultTaxRateBps() : 0, profile.taxLabel(), 0, 0, 0, 0,
                    sellerState, buyerState, "NONE", base);
        }
        long tax = roundedTax(base, profile.defaultTaxRateBps());
        String classification;
        long cgst = 0, sgst = 0, igst = 0;
        if (!sellerState.isBlank() && buyerState.isBlank()) {
            classification = "PROVISIONAL_LOCAL";
            cgst = tax / 2;
            sgst = tax - cgst;
        } else if (sellerState.isBlank() || buyerState.isBlank()) {
            classification = "UNCLASSIFIED";
        } else if (sellerState.equals(buyerState)) {
            classification = "LOCAL";
            cgst = tax / 2;
            sgst = tax - cgst;
        } else {
            classification = "INTERSTATE";
            igst = tax;
        }
        long total;
        try { total = Math.addExact(base, tax); }
        catch (ArithmeticException ex) { throw new IllegalArgumentException("Sale amount including tax is too large.", ex); }
        return new TaxSnapshot(base, profile.defaultTaxRateBps(), profile.taxLabel(), tax, cgst, sgst, igst,
                sellerState, buyerState, classification, total);
    }

    private static long roundedTax(long basePaise, int rateBps) {
        // Half-up rounding in integer paise: (base * bps / 10,000).
        return BigInteger.valueOf(basePaise).multiply(BigInteger.valueOf(rateBps))
                .add(BigInteger.valueOf(5000)).divide(BigInteger.valueOf(10000)).longValueExact();
    }

    private static String normalizeState(String raw, String label) {
        String value = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        if (!value.isEmpty() && !value.matches("[0-9]{2}")) {
            throw new IllegalArgumentException(label + " state code must contain two digits.");
        }
        return value;
    }

    public record TaxSnapshot(long taxableBasePaise, int taxRateBps, String taxLabel, long taxAmountPaise,
                              long cgstPaise, long sgstPaise, long igstPaise, String sellerStateCode,
                              String buyerStateCode, String classification, long totalPaise) { }
}
