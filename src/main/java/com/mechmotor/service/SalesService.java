package com.mechmotor.service;

import com.mechmotor.domain.CartSaleDraft;
import com.mechmotor.domain.CartSaleReceipt;
import com.mechmotor.domain.PartDetails;
import com.mechmotor.domain.SaleDraft;
import com.mechmotor.domain.SaleLineDraft;
import com.mechmotor.domain.SaleLineReceipt;
import com.mechmotor.domain.SaleReceipt;
import com.mechmotor.domain.SaleVoidReceipt;
import com.mechmotor.domain.SaleReturnDraft;
import com.mechmotor.domain.SaleReturnable;
import com.mechmotor.domain.SaleReturnReceipt;
import com.mechmotor.domain.SaleHistoryRow;
import com.mechmotor.repository.PartRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.math.BigInteger;
import java.util.UUID;

/** Posts sales atomically, including multi-item carts, stock deduction, ledger movements and payment. */
@RequiresFeature("SALES")
@Service
public class SalesService {
    private final JdbcTemplate jdbc;
    private final PartRepository parts;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public SalesService(JdbcTemplate jdbc, PartRepository parts, PlatformTransactionManager transactionManager) {
        this(jdbc, parts, transactionManager, Clock.systemUTC());
    }

    SalesService(JdbcTemplate jdbc, PartRepository parts, PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = jdbc;
        this.parts = parts;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Read-only recent-sales history with bounded results and literal substring search. */
    public List<SaleHistoryRow> history(String rawQuery, int requestedLimit) {
        String query = rawQuery == null ? "" : rawQuery.strip().replaceAll("\\s+", " ");
        if (query.length() > 120) throw new IllegalArgumentException("Sales history search cannot exceed 120 characters.");
        int limit = Math.max(1, Math.min(requestedLimit, 500));
        String pattern = "%" + escapeLike(query) + "%";
        return jdbc.query("""
            SELECT s.id, s.sale_number, COALESCE(s.customer_name,'') AS customer_name,
                   s.created_at, s.subtotal_paise, s.discount_paise, s.total_paise, s.status,
                   COALESCE((SELECT group_concat(method, ', ') FROM payments p WHERE p.sale_id=s.id),'') AS payment_methods,
                   COALESCE((SELECT SUM(si.quantity) FROM sale_items si WHERE si.sale_id=s.id),0) AS units,
                   COALESCE((SELECT SUM(v.amount_refunded_paise) FROM sale_voids v WHERE v.sale_id=s.id),0)
                     + COALESCE((SELECT SUM(r.refund_paise) FROM sale_returns r WHERE r.sale_id=s.id),0) AS refunded_paise
            FROM sales s
            WHERE (? = '' OR s.sale_number LIKE ? ESCAPE '!' OR COALESCE(s.customer_name,'') LIKE ? ESCAPE '!')
            ORDER BY s.created_at DESC, s.id DESC
            LIMIT ?
            """, (rs, row) -> new SaleHistoryRow(
                rs.getLong("id"), rs.getString("sale_number"), rs.getString("customer_name"),
                rs.getString("created_at"), rs.getLong("subtotal_paise"), rs.getLong("discount_paise"),
                rs.getLong("total_paise"), rs.getString("status"), rs.getString("payment_methods"),
                rs.getInt("units"), rs.getLong("refunded_paise")),
                query, pattern, pattern, limit);
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    public SaleVoidReceipt voidSale(String rawSaleNumber, String rawReason, String rawRefundReference) {
        String saleNumber = rawSaleNumber == null ? "" : rawSaleNumber.strip();
        if (saleNumber.isEmpty() || saleNumber.length() > 64) {
            throw new IllegalArgumentException("Enter a valid sale number.");
        }
        String reason = rawReason == null ? "" : rawReason.strip().replaceAll("\\s+", " ");
        if (reason.isEmpty()) throw new IllegalArgumentException("A reason is required to void a sale.");
        if (reason.length() > 240) throw new IllegalArgumentException("Void reason cannot exceed 240 characters.");
        String refundReference = rawRefundReference == null || rawRefundReference.isBlank()
                ? null : rawRefundReference.strip().replaceAll("\\s+", " ");
        if (refundReference != null && refundReference.length() > 120) {
            throw new IllegalArgumentException("Refund reference cannot exceed 120 characters.");
        }

        return transactions.execute(status -> {
            List<SaleHeader> headers = jdbc.query(
                    "SELECT id,sale_number,total_paise,status FROM sales WHERE sale_number=? COLLATE NOCASE",
                    (rs, row) -> new SaleHeader(rs.getLong("id"), rs.getString("sale_number"),
                            rs.getLong("total_paise"), rs.getString("status")), saleNumber);
            if (headers.isEmpty()) throw new IllegalArgumentException("Sale number was not found.");
            SaleHeader sale = headers.get(0);
            if (!"POSTED".equals(sale.status())) {
                throw new IllegalStateException("Only a posted sale can be voided. This sale is already " + sale.status() + ".");
            }

            List<SaleLine> lines = jdbc.query(
                    "SELECT part_id,quantity FROM sale_items WHERE sale_id=? ORDER BY id",
                    (rs, row) -> new SaleLine(rs.getLong("part_id"), rs.getInt("quantity")), sale.id());
            if (lines.isEmpty()) throw new IllegalStateException("Sale has no item lines; no changes were made.");

            String refundMethod = null;
            if (sale.totalPaise() > 0) {
                List<String> methods = jdbc.query(
                        "SELECT method FROM payments WHERE sale_id=? ORDER BY id LIMIT 1",
                        (rs, row) -> rs.getString("method"), sale.id());
                if (methods.isEmpty()) {
                    throw new IllegalStateException("Sale payment record is missing. Resolve the payment audit before voiding.");
                }
                refundMethod = methods.get(0);
            }

            Integer priorReturns = jdbc.queryForObject("SELECT COUNT(*) FROM sale_returns WHERE sale_id=?", Integer.class, sale.id());
            if (priorReturns != null && priorReturns > 0) {
                throw new IllegalStateException("This sale already has returned items. Use the returns workflow for remaining items.");
            }

            int marked = jdbc.update("UPDATE sales SET status='VOIDED' WHERE id=? AND status='POSTED'", sale.id());
            if (marked != 1) throw new IllegalStateException("Sale status changed. Reload and try again.");

            for (SaleLine line : lines) {
                int restored = jdbc.update("""
                    UPDATE inventory SET quantity_on_hand=quantity_on_hand+?
                    WHERE part_id=? AND quantity_on_hand<=?
                    """, line.quantity(), line.partId(), Integer.MAX_VALUE - line.quantity());
                if (restored != 1) {
                    throw new IllegalStateException("Could not safely restore stock for a sale item; the void was rolled back.");
                }
                jdbc.update("""
                    INSERT INTO stock_movements(part_id,movement_type,quantity,reference,reason)
                    VALUES (?,'REVERSAL',?,?,?)
                    """, line.partId(), line.quantity(), sale.saleNumber(), "Sale void: " + reason);
            }

            jdbc.update("""
                INSERT INTO sale_voids(sale_id,reason,amount_refunded_paise,refund_method,refund_reference)
                VALUES (?,?,?,?,?)
                """, sale.id(), reason, sale.totalPaise(), refundMethod, refundReference);

            return new SaleVoidReceipt(sale.id(), sale.saleNumber(), lines.size(),
                    lines.stream().mapToInt(SaleLine::quantity).sum(), sale.totalPaise(), refundMethod, reason);
        });
    }

    private record SaleHeader(long id, String saleNumber, long totalPaise, String status) { }
    private record SaleLine(long partId, int quantity) { }

    /** Backward-compatible single-item checkout entry point. */

    public List<SaleReturnable> returnableSale(String rawSaleNumber) {
        return loadReturnableSale(normalizeSaleNumber(rawSaleNumber));
    }

    public SaleReturnReceipt returnSale(SaleReturnDraft draft) {
        return transactions.execute(status -> {
            List<SaleReturnable> lines = loadReturnableSale(normalizeSaleNumber(draft.saleNumber()));
            SaleReturnable line = lines.stream()
                    .filter(candidate -> candidate.saleItemId() == draft.saleItemId())
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Choose a line from this sale."));
            if (draft.quantity() > line.remainingQuantity()) {
                throw new IllegalArgumentException("Only " + line.remainingQuantity() + " unit(s) remain eligible for this item.");
            }
            int cumulativeQuantity = Math.addExact(line.returnedQuantity(), draft.quantity());
            long targetRefund = BigInteger.valueOf(line.lineNetTotalPaise())
                    .multiply(BigInteger.valueOf(cumulativeQuantity))
                    .divide(BigInteger.valueOf(line.soldQuantity())).longValueExact();
            long refundAmount = Math.subtractExact(targetRefund, line.refundedPaise());
            if (refundAmount < 0 || refundAmount > line.remainingRefundPaise()) {
                throw new IllegalStateException("Calculated refund exceeds the remaining refundable amount.");
            }

            String returnNumber = "RET-" + LocalDate.now(clock).toString().replace("-", "")
                    + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
            jdbc.update("""
                INSERT INTO sale_returns(return_number,sale_id,sale_item_id,quantity,refund_paise,refund_method,refund_reference,reason)
                VALUES (?,?,?,?,?,?,?,?)
                """, returnNumber, line.saleId(), line.saleItemId(), draft.quantity(), refundAmount,
                    draft.refundMethod(), draft.refundReference(), draft.reason());

            int changed = jdbc.update("""
                INSERT INTO inventory(part_id,quantity_on_hand,reorder_level) VALUES (?,?,0)
                ON CONFLICT(part_id) DO UPDATE
                  SET quantity_on_hand=inventory.quantity_on_hand+excluded.quantity_on_hand
                  WHERE inventory.quantity_on_hand <= ?
                """, line.partId(), draft.quantity(), Integer.MAX_VALUE - draft.quantity());
            if (changed != 1) throw new IllegalStateException("Inventory quantity limit exceeded; no return was posted.");
            jdbc.update("""
                INSERT INTO stock_movements(part_id,movement_type,quantity,reference,reason)
                VALUES (?,'RETURN',?,?,?)
                """, line.partId(), draft.quantity(), returnNumber, "Customer return for " + line.saleNumber());

            return new SaleReturnReceipt(returnNumber, line.saleNumber(), line.sku(), line.partName(),
                    draft.quantity(), refundAmount, draft.refundMethod());
        });
    }

    private List<SaleReturnable> loadReturnableSale(String saleNumber) {
        List<RawReturnLine> rawLines = jdbc.query("""
            SELECT s.id AS sale_id,s.sale_number,s.customer_name,s.subtotal_paise,s.discount_paise,s.tax_amount_paise,
                   si.id AS sale_item_id,si.part_id,si.sku_snapshot,si.name_snapshot,
                   si.quantity AS sold_quantity,si.line_total_paise,
                   COALESCE(r.returned_quantity,0) AS returned_quantity,
                   COALESCE(r.refunded_paise,0) AS refunded_paise
            FROM sales s JOIN sale_items si ON si.sale_id=s.id
            LEFT JOIN (
                SELECT sale_item_id,SUM(quantity) AS returned_quantity,SUM(refund_paise) AS refunded_paise
                FROM sale_returns GROUP BY sale_item_id
            ) r ON r.sale_item_id=si.id
            WHERE s.sale_number=? COLLATE NOCASE AND s.status='POSTED'
            ORDER BY si.id
            """, (rs, row) -> new RawReturnLine(
                rs.getLong("sale_id"), rs.getString("sale_number"), rs.getString("customer_name"),
                rs.getLong("subtotal_paise"), rs.getLong("discount_paise"), rs.getLong("tax_amount_paise"),
                rs.getLong("sale_item_id"), rs.getLong("part_id"), rs.getString("sku_snapshot"), rs.getString("name_snapshot"),
                rs.getInt("sold_quantity"), rs.getLong("line_total_paise"),
                rs.getInt("returned_quantity"), rs.getLong("refunded_paise")), saleNumber);
        if (rawLines.isEmpty()) throw new IllegalArgumentException("No posted sale found with that sale number.");

        long[] discountAllocations = new long[rawLines.size()];
        long allocatedDiscount = 0;
        for (int i = 0; i < rawLines.size(); i++) {
            RawReturnLine raw = rawLines.get(i);
            long allocation = raw.subtotalPaise() == 0 ? 0
                    : BigInteger.valueOf(raw.discountPaise())
                        .multiply(BigInteger.valueOf(raw.lineTotalPaise()))
                        .divide(BigInteger.valueOf(raw.subtotalPaise())).longValueExact();
            discountAllocations[i] = allocation;
            allocatedDiscount = Math.addExact(allocatedDiscount, allocation);
        }
        long remainder = rawLines.get(0).discountPaise() - allocatedDiscount;
        for (int i = rawLines.size() - 1; i >= 0 && remainder > 0; i--) {
            if (rawLines.get(i).lineTotalPaise() > 0) {
                discountAllocations[i] = Math.addExact(discountAllocations[i], remainder);
                remainder = 0;
            }
        }
        if (remainder != 0) throw new IllegalStateException("Could not allocate sale discount to returnable lines.");

        long[] taxAllocations = new long[rawLines.size()];
        long discountedBase = 0;
        for (int i = 0; i < rawLines.size(); i++) {
            discountedBase = Math.addExact(discountedBase, rawLines.get(i).lineTotalPaise() - discountAllocations[i]);
        }
        long allocatedTax = 0;
        long saleTax = rawLines.get(0).taxAmountPaise();
        for (int i = 0; i < rawLines.size(); i++) {
            long lineBase = rawLines.get(i).lineTotalPaise() - discountAllocations[i];
            long allocation = discountedBase == 0 ? 0 : BigInteger.valueOf(saleTax)
                    .multiply(BigInteger.valueOf(lineBase)).divide(BigInteger.valueOf(discountedBase)).longValueExact();
            taxAllocations[i] = allocation;
            allocatedTax = Math.addExact(allocatedTax, allocation);
        }
        long taxRemainder = saleTax - allocatedTax;
        for (int i = rawLines.size() - 1; i >= 0 && taxRemainder > 0; i--) {
            if (rawLines.get(i).lineTotalPaise() - discountAllocations[i] > 0) {
                taxAllocations[i] = Math.addExact(taxAllocations[i], taxRemainder);
                taxRemainder = 0;
            }
        }
        if (taxRemainder != 0) throw new IllegalStateException("Could not allocate sale tax to returnable lines.");

        List<SaleReturnable> result = new ArrayList<>(rawLines.size());
        for (int i = 0; i < rawLines.size(); i++) {
            RawReturnLine raw = rawLines.get(i);
            long lineNet = Math.addExact(raw.lineTotalPaise() - discountAllocations[i], taxAllocations[i]);
            if (lineNet < 0) throw new IllegalStateException("Sale discount allocation is invalid for " + raw.sku() + ".");
            if (raw.returnedQuantity() > raw.soldQuantity()) {
                throw new IllegalStateException("Return history exceeds the original sold quantity for " + raw.sku() + ".");
            }
            int remaining = raw.soldQuantity() - raw.returnedQuantity();
            result.add(new SaleReturnable(raw.saleId(), raw.saleNumber(), raw.customerName(),
                    raw.saleItemId(), raw.partId(), raw.sku(), raw.partName(), raw.soldQuantity(),
                    raw.returnedQuantity(), remaining, lineNet, raw.refundedPaise(),
                    Math.max(0, lineNet - raw.refundedPaise())));
        }
        return List.copyOf(result);
    }

    private static String normalizeSaleNumber(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Enter a sale number.");
        String value = raw.strip().toUpperCase(java.util.Locale.ROOT);
        if (value.length() > 64) throw new IllegalArgumentException("Sale number is too long.");
        return value;
    }

    private record RawReturnLine(long saleId, String saleNumber, String customerName,
                                 long subtotalPaise, long discountPaise, long taxAmountPaise, long saleItemId, long partId,
                                 String sku, String partName, int soldQuantity, long lineTotalPaise,
                                 int returnedQuantity, long refundedPaise) { }

    public SaleReceipt checkout(SaleDraft draft) {
        CartSaleReceipt receipt = checkout(new CartSaleDraft(
                List.of(new SaleLineDraft(draft.partId(), draft.quantity(), draft.unitPricePaise())),
                draft.discountPaise(), draft.customerName(), draft.paymentMethod(), draft.paymentReference()));
        SaleLineReceipt line = receipt.lines().get(0);
        return new SaleReceipt(receipt.saleId(), receipt.saleNumber(), line.sku(), line.partName(),
                line.quantity(), receipt.subtotalPaise(), receipt.discountPaise(),
                receipt.totalPaise(), receipt.paymentMethod());
    }

    public CartSaleReceipt checkout(CartSaleDraft draft) {
        return checkout(draft, null);
    }

    /** Optional buyer state enables tax classification; absent state is explicitly provisional. */
    public CartSaleReceipt checkout(CartSaleDraft draft, String buyerStateCode) {
        return transactions.execute(status -> {
            List<PartDetails> cartParts = new ArrayList<>(draft.lines().size());
            long subtotal = 0;
            List<SaleLineReceipt> receiptLines = new ArrayList<>(draft.lines().size());
            for (SaleLineDraft line : draft.lines()) {
                PartDetails part = parts.findDetailsById(line.partId());
                if (part.quantity() < line.quantity()) {
                    throw new IllegalStateException("Insufficient stock for " + part.sku()
                            + ". Available quantity: " + part.quantity() + ".");
                }
                long lineTotal;
                try {
                    lineTotal = Math.multiplyExact((long) line.quantity(), line.unitPricePaise());
                    subtotal = Math.addExact(subtotal, lineTotal);
                } catch (ArithmeticException ex) {
                    throw new IllegalArgumentException("Sale amount is too large.", ex);
                }
                cartParts.add(part);
                receiptLines.add(new SaleLineReceipt(part.id(), part.sku(), part.name(),
                        line.quantity(), line.unitPricePaise(), lineTotal));
            }
            if (draft.discountPaise() > subtotal) {
                throw new IllegalArgumentException("Discount cannot exceed the sale subtotal.");
            }
            ShopProfileService.ShopProfile taxProfile = new ShopProfileService(jdbc).load();
            TaxCalculationService.TaxSnapshot tax = new TaxCalculationService().calculate(
                    subtotal, draft.discountPaise(), taxProfile, buyerStateCode);
            long total = tax.totalPaise();
            String saleNumber = "MM-" + LocalDate.now(clock).toString().replace("-", "")
                    + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);

            final long saleSubtotal = subtotal;
            KeyHolder key = new GeneratedKeyHolder();
            jdbc.update((PreparedStatementCreator) connection -> {
                PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO sales(sale_number,customer_name,subtotal_paise,discount_paise,total_paise,status,
                      taxable_base_paise,tax_rate_bps,tax_label_snapshot,tax_amount_paise,cgst_paise,sgst_paise,igst_paise,
                      seller_state_code_snapshot,buyer_state_code_snapshot,tax_classification)
                    VALUES (?,?,?,?,?,'POSTED',?,?,?,?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, saleNumber);
                ps.setString(2, draft.customerName());
                ps.setLong(3, saleSubtotal);
                ps.setLong(4, draft.discountPaise());
                ps.setLong(5, total);
                ps.setLong(6, tax.taxableBasePaise());
                ps.setInt(7, tax.taxRateBps());
                ps.setString(8, tax.taxLabel());
                ps.setLong(9, tax.taxAmountPaise());
                ps.setLong(10, tax.cgstPaise());
                ps.setLong(11, tax.sgstPaise());
                ps.setLong(12, tax.igstPaise());
                ps.setString(13, tax.sellerStateCode());
                ps.setString(14, tax.buyerStateCode());
                ps.setString(15, tax.classification());
                return ps;
            }, key);
            Number generated = key.getKey();
            if (generated == null) throw new IllegalStateException("Database did not return the sale ID.");
            long saleId = generated.longValue();

            for (int i = 0; i < draft.lines().size(); i++) {
                SaleLineDraft line = draft.lines().get(i);
                PartDetails part = cartParts.get(i);
                SaleLineReceipt receiptLine = receiptLines.get(i);
                jdbc.update("""
                    INSERT INTO sale_items(sale_id,part_id,sku_snapshot,name_snapshot,quantity,unit_price_paise,line_total_paise)
                    VALUES (?,?,?,?,?,?,?)
                    """, saleId, part.id(), part.sku(), part.name(), line.quantity(),
                        line.unitPricePaise(), receiptLine.lineTotalPaise());

                int changed = jdbc.update("""
                    UPDATE inventory SET quantity_on_hand=quantity_on_hand-?
                    WHERE part_id=? AND quantity_on_hand>=?
                    """, line.quantity(), part.id(), line.quantity());
                if (changed != 1) {
                    throw new IllegalStateException("Stock changed during checkout for " + part.sku()
                            + ". No sale was posted; reload and try again.");
                }
                jdbc.update("""
                    INSERT INTO stock_movements(part_id,movement_type,quantity,reference,reason)
                    VALUES (?,'SALE',?,?,?)
                    """, part.id(), -line.quantity(), saleNumber, "Sale checkout");
            }
            if (total > 0) {
                jdbc.update("""
                    INSERT INTO payments(sale_id,method,amount_paise,reference)
                    VALUES (?,?,?,?)
                    """, saleId, draft.paymentMethod(), total, draft.paymentReference());
            }
            return new CartSaleReceipt(saleId, saleNumber, receiptLines, subtotal,
                    draft.discountPaise(), total, draft.paymentMethod());
        });
    }
}
