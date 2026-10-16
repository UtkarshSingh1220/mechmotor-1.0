package com.mechmotor.service;

import com.mechmotor.domain.PartDetails;
import com.mechmotor.domain.SaleDraft;
import com.mechmotor.domain.SaleReceipt;
import com.mechmotor.domain.SaleReturnDraft;
import com.mechmotor.domain.SaleReturnable;
import com.mechmotor.domain.SaleReturnReceipt;
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
import java.math.BigInteger;
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

    public SaleReturnable returnableSale(String rawSaleNumber) {
        return loadReturnableSale(normalizeSaleNumber(rawSaleNumber));
    }

    public SaleReturnReceipt returnSale(SaleReturnDraft draft) {
        return transactions.execute(status -> {
            SaleReturnable sale = loadReturnableSale(normalizeSaleNumber(draft.saleNumber()));
            if (draft.quantity() > sale.remainingQuantity()) {
                throw new IllegalArgumentException("Only " + sale.remainingQuantity() + " unit(s) remain eligible for return.");
            }
            int cumulativeQuantity = Math.addExact(sale.returnedQuantity(), draft.quantity());
            long targetRefund = BigInteger.valueOf(sale.saleTotalPaise())
                    .multiply(BigInteger.valueOf(cumulativeQuantity))
                    .divide(BigInteger.valueOf(sale.soldQuantity())).longValueExact();
            long refundAmount = Math.subtractExact(targetRefund, sale.refundedPaise());
            if (refundAmount < 0 || refundAmount > sale.remainingRefundPaise()) {
                throw new IllegalStateException("Calculated refund exceeds the remaining refundable amount.");
            }

            String returnNumber = "RET-" + LocalDate.now(clock).toString().replace("-", "")
                    + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
            jdbc.update("""
                INSERT INTO sale_returns(return_number,sale_id,quantity,refund_paise,refund_method,refund_reference,reason)
                VALUES (?,?,?,?,?,?,?)
                """, returnNumber, sale.saleId(), draft.quantity(), refundAmount,
                    draft.refundMethod(), draft.refundReference(), draft.reason());

            int changed = jdbc.update("""
                INSERT INTO inventory(part_id,quantity_on_hand,reorder_level) VALUES (?,?,0)
                ON CONFLICT(part_id) DO UPDATE
                  SET quantity_on_hand=inventory.quantity_on_hand+excluded.quantity_on_hand
                  WHERE inventory.quantity_on_hand <= ?
                """, sale.partId(), draft.quantity(), Integer.MAX_VALUE - draft.quantity());
            if (changed != 1) {
                throw new IllegalStateException("Inventory quantity limit exceeded; no return was posted.");
            }
            jdbc.update("""
                INSERT INTO stock_movements(part_id,movement_type,quantity,reference,reason)
                VALUES (?,'RETURN',?,?,?)
                """, sale.partId(), draft.quantity(), returnNumber, "Customer return for " + sale.saleNumber());

            return new SaleReturnReceipt(returnNumber, sale.saleNumber(), sale.sku(), sale.partName(),
                    draft.quantity(), refundAmount, draft.refundMethod());
        });
    }

    private SaleReturnable loadReturnableSale(String saleNumber) {
        List<SaleReturnable> sales = jdbc.query("""
            SELECT s.id AS sale_id, s.sale_number, s.customer_name, s.total_paise,
                   si.part_id, si.sku_snapshot, si.name_snapshot, si.quantity AS sold_quantity,
                   COALESCE(r.returned_quantity,0) AS returned_quantity,
                   COALESCE(r.refunded_paise,0) AS refunded_paise
            FROM sales s
            JOIN sale_items si ON si.sale_id=s.id
            LEFT JOIN (
                SELECT sale_id, SUM(quantity) AS returned_quantity, SUM(refund_paise) AS refunded_paise
                FROM sale_returns GROUP BY sale_id
            ) r ON r.sale_id=s.id
            WHERE s.sale_number=? AND s.status='POSTED'
            """, (rs, row) -> {
                int soldQuantity = rs.getInt("sold_quantity");
                int returnedQuantity = rs.getInt("returned_quantity");
                long saleTotal = rs.getLong("total_paise");
                long refunded = rs.getLong("refunded_paise");
                return new SaleReturnable(rs.getLong("sale_id"), rs.getString("sale_number"),
                        rs.getString("customer_name"), rs.getLong("part_id"),
                        rs.getString("sku_snapshot"), rs.getString("name_snapshot"),
                        soldQuantity, returnedQuantity, Math.max(0, soldQuantity - returnedQuantity),
                        saleTotal, refunded, Math.max(0, saleTotal - refunded));
            }, saleNumber);
        if (sales.isEmpty()) throw new IllegalArgumentException("No posted sale found with that sale number.");
        if (sales.size() != 1) throw new IllegalStateException("This version only supports returns for single-item sales.");
        return sales.get(0);
    }

    private static String normalizeSaleNumber(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Enter a sale number.");
        String value = raw.strip().toUpperCase(java.util.Locale.ROOT);
        if (value.length() > 40) throw new IllegalArgumentException("Sale number is too long.");
        return value;
    }
}
