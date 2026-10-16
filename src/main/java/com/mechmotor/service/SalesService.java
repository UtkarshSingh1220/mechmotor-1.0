package com.mechmotor.service;

import com.mechmotor.domain.PartDetails;
import com.mechmotor.domain.SaleDraft;
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
import java.util.UUID;

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
}
