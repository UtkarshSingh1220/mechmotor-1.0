package com.mechmotor.service;

import com.mechmotor.domain.LookupOption;
import com.mechmotor.domain.PartDetails;
import com.mechmotor.domain.PurchaseDraft;
import com.mechmotor.domain.PurchaseReceipt;
import com.mechmotor.domain.PurchaseHistoryRow;
import com.mechmotor.domain.PurchaseCancellationReceipt;
import com.mechmotor.domain.PurchaseLineDraft;
import com.mechmotor.domain.PurchaseOrderDraft;
import com.mechmotor.domain.PurchaseOrderReceipt;
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


    /** Search recent supplier receipts; results are deliberately bounded for a desktop view. */
    public List<PurchaseHistoryRow> history(String rawQuery, int requestedLimit) {
        String query = rawQuery == null ? "" : rawQuery.strip().replaceAll("\\s+", " ");
        if (query.length() > 120) throw new IllegalArgumentException("Purchase history search cannot exceed 120 characters.");
        int limit = Math.max(1, Math.min(requestedLimit, 500));
        String pattern = "%" + escapeLike(query) + "%";
        return jdbc.query("""
            SELECT p.id,p.purchase_number,p.supplier_name_snapshot,
                   COALESCE(p.supplier_invoice_reference,'') AS supplier_invoice_reference,
                   p.created_at,p.subtotal_paise,p.status,
                   COALESCE((SELECT SUM(pi.quantity) FROM purchase_items pi WHERE pi.purchase_id=p.id),0) AS units,
                   COALESCE((SELECT group_concat(pi.sku_snapshot || ' x' || pi.quantity, ', ')
                             FROM purchase_items pi WHERE pi.purchase_id=p.id),'') AS item_summary,
                   COALESCE((SELECT c.reason FROM purchase_cancellations c WHERE c.purchase_id=p.id),'') AS cancellation_reason
            FROM purchases p
            WHERE (?='' OR p.purchase_number LIKE ? ESCAPE '!'
                OR p.supplier_name_snapshot LIKE ? ESCAPE '!'
                OR COALESCE(p.supplier_invoice_reference,'') LIKE ? ESCAPE '!'
                OR EXISTS (SELECT 1 FROM purchase_items pi WHERE pi.purchase_id=p.id
                    AND (pi.sku_snapshot LIKE ? ESCAPE '!' OR pi.name_snapshot LIKE ? ESCAPE '!')))
            ORDER BY p.created_at DESC,p.id DESC
            LIMIT ?
            """, (rs,row) -> new PurchaseHistoryRow(
                rs.getLong("id"),rs.getString("purchase_number"),rs.getString("supplier_name_snapshot"),
                rs.getString("supplier_invoice_reference"),rs.getString("created_at"),rs.getLong("subtotal_paise"),
                rs.getString("status"),rs.getInt("units"),rs.getString("item_summary"),rs.getString("cancellation_reason")),
                query,pattern,pattern,pattern,pattern,pattern,limit);
    }

    /**
     * Cancels an un-settled stock receipt only when current on-hand quantity can
     * cover the full reversal. This app does not track inventory lots or supplier refunds.
     */
    public PurchaseCancellationReceipt cancelPurchase(String rawPurchaseNumber, String rawReason) {
        String purchaseNumber = rawPurchaseNumber == null ? "" : rawPurchaseNumber.strip();
        if (purchaseNumber.isEmpty() || purchaseNumber.length() > 64) {
            throw new IllegalArgumentException("Enter a valid purchase receipt number.");
        }
        String reason = rawReason == null ? "" : rawReason.strip().replaceAll("\\s+", " ");
        if (reason.isEmpty()) throw new IllegalArgumentException("A cancellation reason is required.");
        if (reason.length() > 240) throw new IllegalArgumentException("Cancellation reason cannot exceed 240 characters.");

        return transactions.execute(status -> {
            List<PurchaseHeader> headers = jdbc.query(
                    "SELECT id,purchase_number,status FROM purchases WHERE purchase_number=? COLLATE NOCASE",
                    (rs,row) -> new PurchaseHeader(rs.getLong("id"),rs.getString("purchase_number"),rs.getString("status")),
                    purchaseNumber);
            if (headers.isEmpty()) throw new IllegalArgumentException("Purchase receipt was not found.");
            PurchaseHeader purchase = headers.get(0);
            if (!"RECEIVED".equals(purchase.status())) {
                throw new IllegalStateException("Only a RECEIVED purchase can be cancelled. This receipt is already " + purchase.status() + ".");
            }
            List<PurchaseLine> lines = jdbc.query("""
                SELECT part_id,sku_snapshot,quantity,unit_cost_paise,previous_purchase_price_paise
                FROM purchase_items WHERE purchase_id=? ORDER BY id
                """, (rs,row) -> {
                    long previousCost = rs.getLong("previous_purchase_price_paise");
                    Long previousPurchaseCost = rs.wasNull() ? null : previousCost;
                    return new PurchaseLine(rs.getLong("part_id"),rs.getString("sku_snapshot"),
                            rs.getInt("quantity"),rs.getLong("unit_cost_paise"),previousPurchaseCost);
                }, purchase.id());
            if (lines.isEmpty()) throw new IllegalStateException("Purchase receipt has no item lines; no changes were made.");

            for (PurchaseLine line : lines) {
                int changed = jdbc.update("""
                    UPDATE inventory SET quantity_on_hand=quantity_on_hand-?
                    WHERE part_id=? AND quantity_on_hand-? >= reserved_quantity
                    """, line.quantity(),line.partId(),line.quantity());
                if (changed != 1) {
                    throw new IllegalStateException("Cannot cancel receipt: current stock for " + line.sku()
                            + " is below the received quantity. No changes were committed.");
                }
            }

            int marked = jdbc.update("UPDATE purchases SET status='CANCELLED' WHERE id=? AND status='RECEIVED'",purchase.id());
            if (marked != 1) throw new IllegalStateException("Purchase status changed. Reload history and try again.");

            jdbc.update("INSERT INTO purchase_cancellations(purchase_id,reason) VALUES (?,?)",purchase.id(),reason);
            int totalUnits = 0;
            for (PurchaseLine line : lines) {
                totalUnits = Math.addExact(totalUnits,line.quantity());
                jdbc.update("""
                    INSERT INTO stock_movements(part_id,movement_type,quantity,reference,reason)
                    VALUES (?,'REVERSAL',?,?,?)
                    """,line.partId(),-line.quantity(),purchase.purchaseNumber(),"Purchase cancellation: "+reason);

                // Old receipts have no reliable prior-cost snapshot. Never guess a cost
                // for those rows; only restore cost when this receipt recorded it.
                if (line.previousPurchasePricePaise() != null) {
                    jdbc.update("""
                        UPDATE parts SET purchase_price_paise=?,updated_at=CURRENT_TIMESTAMP
                        WHERE id=? AND purchase_price_paise=?
                          AND NOT EXISTS (
                            SELECT 1 FROM purchase_items later_item
                            JOIN purchases later_purchase ON later_purchase.id=later_item.purchase_id
                            WHERE later_item.part_id=? AND later_purchase.status='RECEIVED'
                              AND later_purchase.id>?
                          )
                        """,line.previousPurchasePricePaise(),line.partId(),line.unitCostPaise(),line.partId(),purchase.id());
                }
            }
            long total = jdbc.queryForObject("SELECT subtotal_paise FROM purchases WHERE id=?",Long.class,purchase.id());
            return new PurchaseCancellationReceipt(purchase.purchaseNumber(),reason,lines.size(),totalUnits,total);
        });
    }

    private record PurchaseHeader(long id,String purchaseNumber,String status) { }
    private record PurchaseLine(long partId,String sku,int quantity,long unitCostPaise,Long previousPurchasePricePaise) { }

    private static String escapeLike(String value) {
        return value.replace("!","!!").replace("%","!%").replace("_","!_");
    }


    public PurchaseReceipt receive(PurchaseDraft draft) {
        PurchaseOrderReceipt order = receive(new PurchaseOrderDraft(
                draft.supplierId(), draft.supplierInvoiceReference(),
                List.of(new PurchaseLineDraft(draft.partId(), draft.quantity(), draft.unitCostPaise()))));
        PartDetails part = parts.findDetailsById(draft.partId());
        long lineTotal = Math.multiplyExact((long) draft.quantity(), draft.unitCostPaise());
        return new PurchaseReceipt(order.purchaseId(), order.purchaseNumber(), part.sku(), part.name(),
                order.supplierName(), draft.quantity(), draft.unitCostPaise(), lineTotal);
    }

    /** Atomically receives up to 100 distinct inventory items on one supplier receipt. */
    public PurchaseOrderReceipt receive(PurchaseOrderDraft draft) {
        return transactions.execute(status -> {
            LookupOption supplier = parts.suppliers().stream()
                    .filter(option -> option.id() == draft.supplierId())
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Selected supplier does not exist."));
            List<PreparedPurchaseLine> prepared = new java.util.ArrayList<>();
            long total = 0;
            int totalUnits = 0;
            try {
                for (PurchaseLineDraft line : draft.lines()) {
                    PartDetails part = parts.findDetailsById(line.partId());
                    long lineTotal = Math.multiplyExact((long) line.quantity(), line.unitCostPaise());
                    total = Math.addExact(total, lineTotal);
                    totalUnits = Math.addExact(totalUnits, line.quantity());
                    prepared.add(new PreparedPurchaseLine(part, line, lineTotal));
                }
            } catch (ArithmeticException ex) {
                throw new IllegalArgumentException("Purchase amount or total quantity is too large.", ex);
            }

            String purchaseNumber = "PO-" + LocalDate.now(clock).toString().replace("-", "")
                    + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
            KeyHolder key = new GeneratedKeyHolder();
            long receiptTotal = total;
            jdbc.update((PreparedStatementCreator) connection -> {
                PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO purchases(purchase_number,supplier_id,supplier_name_snapshot,supplier_invoice_reference,subtotal_paise,status)
                    VALUES (?,?,?,?,?,'RECEIVED')
                    """, Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, purchaseNumber);
                ps.setLong(2, supplier.id());
                ps.setString(3, supplier.name());
                ps.setString(4, draft.supplierInvoiceReference());
                ps.setLong(5, receiptTotal);
                return ps;
            }, key);
            Number generated = key.getKey();
            if (generated == null) throw new IllegalStateException("Database did not return the purchase ID.");
            long purchaseId = generated.longValue();

            for (PreparedPurchaseLine item : prepared) {
                PartDetails part = item.part();
                PurchaseLineDraft line = item.draft();
                jdbc.update("""
                    INSERT INTO purchase_items(purchase_id,part_id,sku_snapshot,name_snapshot,quantity,unit_cost_paise,line_total_paise,previous_purchase_price_paise)
                    VALUES (?,?,?,?,?,?,?,?)
                    """, purchaseId, part.id(), part.sku(), part.name(), line.quantity(),
                        line.unitCostPaise(), item.lineTotalPaise(), part.purchasePricePaise());

                int changed = jdbc.update("""
                    INSERT INTO inventory(part_id,quantity_on_hand,reorder_level) VALUES (?,?,0)
                    ON CONFLICT(part_id) DO UPDATE
                      SET quantity_on_hand=inventory.quantity_on_hand+excluded.quantity_on_hand
                      WHERE inventory.quantity_on_hand <= ?
                    """, part.id(), line.quantity(), Integer.MAX_VALUE - line.quantity());
                if (changed != 1) {
                    throw new IllegalStateException("Inventory quantity limit exceeded for SKU " + part.sku() + ".");
                }
                int costUpdated = jdbc.update(
                        "UPDATE parts SET purchase_price_paise=?, updated_at=CURRENT_TIMESTAMP WHERE id=? AND active=1",
                        line.unitCostPaise(), part.id());
                if (costUpdated != 1) throw new IllegalStateException("Inventory item " + part.sku() + " became inactive during receiving.");
                jdbc.update("""
                    INSERT INTO stock_movements(part_id,movement_type,quantity,reference,reason)
                    VALUES (?,'PURCHASE',?,?,?)
                    """, part.id(), line.quantity(), purchaseNumber, "Supplier stock receipt");
            }
            return new PurchaseOrderReceipt(purchaseId, purchaseNumber, supplier.name(),
                    prepared.size(), totalUnits, total);
        });
    }

    private record PreparedPurchaseLine(PartDetails part, PurchaseLineDraft draft, long lineTotalPaise) { }
}
