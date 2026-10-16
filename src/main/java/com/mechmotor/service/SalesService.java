package com.mechmotor.service;

import com.mechmotor.domain.CartSaleDraft;
import com.mechmotor.domain.CartSaleReceipt;
import com.mechmotor.domain.PartDetails;
import com.mechmotor.domain.SaleDraft;
import com.mechmotor.domain.SaleLineDraft;
import com.mechmotor.domain.SaleLineReceipt;
import com.mechmotor.domain.SaleReceipt;
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
import java.util.UUID;

/** Posts sales atomically, including multi-item carts, stock deduction, ledger movements and payment. */
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

    /** Backward-compatible single-item checkout entry point. */
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
            long total = subtotal - draft.discountPaise();
            String saleNumber = "MM-" + LocalDate.now(clock).toString().replace("-", "")
                    + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);

            final long saleSubtotal = subtotal;
            KeyHolder key = new GeneratedKeyHolder();
            jdbc.update((PreparedStatementCreator) connection -> {
                PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO sales(sale_number,customer_name,subtotal_paise,discount_paise,total_paise,status)
                    VALUES (?,?,?,?,?,'POSTED')
                    """, Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, saleNumber);
                ps.setString(2, draft.customerName());
                ps.setLong(3, saleSubtotal);
                ps.setLong(4, draft.discountPaise());
                ps.setLong(5, total);
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
