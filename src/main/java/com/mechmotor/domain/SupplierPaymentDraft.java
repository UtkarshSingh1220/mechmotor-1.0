package com.mechmotor.domain;

public record SupplierPaymentDraft(String purchaseNumber, long amountPaise, Method method, String reference) {
    public enum Method { CASH, UPI, CARD, BANK_TRANSFER, CHEQUE, OTHER }
    public SupplierPaymentDraft {
        purchaseNumber=purchaseNumber==null?"":purchaseNumber.strip();
        if(purchaseNumber.isEmpty()||purchaseNumber.length()>64) throw new IllegalArgumentException("Enter a valid purchase receipt number.");
        if(amountPaise<=0) throw new IllegalArgumentException("Payment amount must be greater than zero.");
        if(method==null) throw new IllegalArgumentException("Choose a payment method.");
        reference=reference==null||reference.isBlank()?null:reference.strip().replaceAll("\\s+"," ");
        if(reference!=null&&reference.length()>120) throw new IllegalArgumentException("Payment reference cannot exceed 120 characters.");
    }
}
