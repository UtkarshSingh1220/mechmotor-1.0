package com.mechmotor.domain;

import java.util.List;

public record CartSaleReceipt(
        long saleId, String saleNumber, List<SaleLineReceipt> lines,
        long subtotalPaise, long discountPaise, long totalPaise, String paymentMethod) {
    public CartSaleReceipt {
        lines = List.copyOf(lines);
    }
}
