package com.mechmotor.service;

import com.mechmotor.domain.PartDetails;
import com.mechmotor.domain.SaleDraft;
import com.mechmotor.domain.SaleReceipt;
import com.mechmotor.domain.SaleVoidReceipt;
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
import java.util.UUID;
import java.util.List;

/** Posts a single-item sale atomically with stock deduction, ledger movement and payment. */
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

    public SaleReceipt checkout(SaleDraft draft) {
        return transactions.execute(status -> {
            PartDetails part = parts.findDetailsById(draft.partId());
            if (part.quantity() < draft.quantity()) {
                throw new IllegalStateException("Insufficient stock. Available quantity: " + part.quantity() + ".");
            }
            long subtotal;
            try {
                subtotal = Math.multiplyExact((long) draft.quantity(), draft.unitPricePaise());
            } catch (ArithmeticException ex) {
                throw new IllegalArgumentException("Sale amount is too large.", ex);
            }
            if (draft.discountPaise() > subtotal) {
                throw new IllegalArgumentException("Discount cannot exceed the sale subtotal.");
            }
            long total = subtotal - draft.discountPaise();
            String saleNumber = "MM-" + LocalDate.now(clock).toString().replace("-", "")
                    + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);

            KeyHolder key = new GeneratedKeyHolder();
            jdbc.update((PreparedStatementCreator) connection -> {
                PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO sales(sale_number,customer_name,subtotal_paise,discount_paise,total_paise,status)
                    VALUES (?,?,?,?,?,'POSTED')
                    """, Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, saleNumber);
                ps.setString(2, draft.customerName());
                ps.setLong(3, subtotal);
                ps.setLong(4, draft.discountPaise());
                ps.setLong(5, total);
                return ps;
            }, key);
            Number generated = key.getKey();
            if (generated == null) throw new IllegalStateException("Database did not return the sale ID.");
            long saleId = generated.longValue();

            jdbc.update("""
                INSERT INTO sale_items(sale_id,part_id,sku_snapshot,name_snapshot,quantity,unit_price_paise,line_total_paise)
                VALUES (?,?,?,?,?,?,?)
                """, saleId, part.id(), part.sku(), part.name(), draft.quantity(), draft.unitPricePaise(), subtotal);

            int changed = jdbc.update("""
                UPDATE inventory SET quantity_on_hand=quantity_on_hand-?
                WHERE part_id=? AND quantity_on_hand>=?
                """, draft.quantity(), part.id(), draft.quantity());
            if (changed != 1) {
                throw new IllegalStateException("Stock changed during checkout. No sale was posted; reload and try again.");
            }

            jdbc.update("""
                INSERT INTO stock_movements(part_id,movement_type,quantity,reference,reason)
                VALUES (?,'SALE',?,?,?)
                """, part.id(), -draft.quantity(), saleNumber, "Sale checkout");
            if (total > 0) {
                jdbc.update("""
                    INSERT INTO payments(sale_id,method,amount_paise,reference)
                    VALUES (?,?,?,?)
                    """, saleId, draft.paymentMethod(), total, draft.paymentReference());
            }
            return new SaleReceipt(saleId, saleNumber, part.sku(), part.name(), draft.quantity(),
                    subtotal, draft.discountPaise(), total, draft.paymentMethod());
        });
    }
}
