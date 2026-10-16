package com.mechmotor.service;

import com.mechmotor.domain.LookupOption;
import com.mechmotor.domain.PartDetails;
import com.mechmotor.domain.PurchaseDraft;
import com.mechmotor.domain.PurchaseReceipt;
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
import java.util.List;
import java.util.UUID;

/** Receives a single inventory item from a supplier in one atomic SQLite transaction. */
@RequiresFeature("PURCHASING")
@Service
public class PurchasingService {
    private final JdbcTemplate jdbc;
    private final PartRepository parts;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public PurchasingService(JdbcTemplate jdbc, PartRepository parts, PlatformTransactionManager transactionManager) {
        this(jdbc, parts, transactionManager, Clock.systemUTC());
    }

    PurchasingService(JdbcTemplate jdbc, PartRepository parts, PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = jdbc;
        this.parts = parts;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public PurchaseReceipt receive(PurchaseDraft draft) {
        return transactions.execute(status -> {
            List<LookupOption> suppliers = parts.suppliers();
            LookupOption supplier = suppliers.stream().filter(option -> option.id() == draft.supplierId())
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Selected supplier does not exist."));
            PartDetails part = parts.findDetailsById(draft.partId());
            long total;
            try {
                total = Math.multiplyExact((long) draft.quantity(), draft.unitCostPaise());
            } catch (ArithmeticException ex) {
                throw new IllegalArgumentException("Purchase amount is too large.", ex);
            }
            String purchaseNumber = "PO-" + LocalDate.now(clock).toString().replace("-", "")
                    + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);

            KeyHolder key = new GeneratedKeyHolder();
            jdbc.update((PreparedStatementCreator) connection -> {
                PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO purchases(purchase_number,supplier_id,supplier_name_snapshot,supplier_invoice_reference,subtotal_paise,status)
                    VALUES (?,?,?,?,?,'RECEIVED')
                    """, Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, purchaseNumber);
                ps.setLong(2, supplier.id());
                ps.setString(3, supplier.name());
                ps.setString(4, draft.supplierInvoiceReference());
                ps.setLong(5, total);
                return ps;
            }, key);
            Number generated = key.getKey();
            if (generated == null) throw new IllegalStateException("Database did not return the purchase ID.");
            long purchaseId = generated.longValue();

            jdbc.update("""
                INSERT INTO purchase_items(purchase_id,part_id,sku_snapshot,name_snapshot,quantity,unit_cost_paise,line_total_paise)
                VALUES (?,?,?,?,?,?,?)
                """, purchaseId, part.id(), part.sku(), part.name(), draft.quantity(), draft.unitCostPaise(), total);

            int changed = jdbc.update("""
                INSERT INTO inventory(part_id,quantity_on_hand,reorder_level) VALUES (?,?,0)
                ON CONFLICT(part_id) DO UPDATE
                  SET quantity_on_hand=inventory.quantity_on_hand+excluded.quantity_on_hand
                  WHERE inventory.quantity_on_hand <= ?
                """, part.id(), draft.quantity(), Integer.MAX_VALUE - draft.quantity());
            if (changed != 1) {
                throw new IllegalStateException("Inventory quantity limit exceeded or inventory row could not be updated.");
            }
            int costUpdated = jdbc.update("UPDATE parts SET purchase_price_paise=?, updated_at=CURRENT_TIMESTAMP WHERE id=? AND active=1",
                    draft.unitCostPaise(), part.id());
            if (costUpdated != 1) throw new IllegalStateException("Inventory item became inactive during receiving.");
            jdbc.update("""
                INSERT INTO stock_movements(part_id,movement_type,quantity,reference,reason)
                VALUES (?,'PURCHASE',?,?,?)
                """, part.id(), draft.quantity(), purchaseNumber, "Supplier stock receipt");

            return new PurchaseReceipt(purchaseId, purchaseNumber, part.sku(), part.name(), supplier.name(),
                    draft.quantity(), draft.unitCostPaise(), total);
        });
    }
}
