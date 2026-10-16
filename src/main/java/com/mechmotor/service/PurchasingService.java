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
import com.mechmotor.domain.SupplierPaymentDraft;
import com.mechmotor.domain.SupplierPaymentReceipt;
import com.mechmotor.domain.SupplierPaymentRow;
import com.mechmotor.domain.PurchaseBalance;
import com.mechmotor.domain.FormalPurchaseOrderDraft;
import com.mechmotor.domain.FormalPurchaseOrderReceipt;
import com.mechmotor.domain.FormalPurchaseOrderRow;
import com.mechmotor.domain.FormalPurchaseOrderLineRow;
import com.mechmotor.domain.PurchaseOrderReceiveLine;
import com.mechmotor.repository.PartRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.BigDecimal;
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
                   COALESCE((SELECT c.reason FROM purchase_cancellations c WHERE c.purchase_id=p.id),'') AS cancellation_reason,
                   COALESCE((SELECT SUM(sp.amount_paise) FROM supplier_payments sp WHERE sp.purchase_id=p.id AND sp.status='POSTED'),0) AS paid_paise,
                   p.subtotal_paise-COALESCE((SELECT SUM(sr.total_paise) FROM supplier_returns sr WHERE sr.purchase_id=p.id AND sr.status='POSTED'),0)-COALESCE((SELECT SUM(sp.amount_paise) FROM supplier_payments sp WHERE sp.purchase_id=p.id AND sp.status='POSTED'),0) AS outstanding_paise
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
                rs.getString("status"),rs.getInt("units"),rs.getString("item_summary"),rs.getString("cancellation_reason"),
                rs.getLong("paid_paise"),rs.getLong("outstanding_paise")),
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
            Long postedPayments = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM supplier_payments WHERE purchase_id=?",
                    Long.class, purchase.id());
            Long postedReturns = jdbc.queryForObject("SELECT COUNT(*) FROM supplier_returns WHERE purchase_id=?",
                    Long.class, purchase.id());
            if (postedReturns != null && postedReturns > 0) {
                throw new IllegalStateException("Cannot cancel a receipt with supplier return history. Use the supplier credit note workflow; no stock changes were committed.");
            }
            if (postedPayments != null && postedPayments > 0) {
                throw new IllegalStateException("Cannot cancel a receipt with supplier payment history. Reconcile the supplier account separately; no stock changes were committed.");
            }

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

    /** Creates a formal order without changing stock. */
    public FormalPurchaseOrderRow createPurchaseOrder(FormalPurchaseOrderDraft draft) {
        return transactions.execute(status -> {
            LookupOption supplier = parts.suppliers().stream()
                    .filter(option -> option.id() == draft.supplierId())
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Selected supplier does not exist."));
            List<PreparedPurchaseLine> prepared = new java.util.ArrayList<>();
            long total = 0;
            try {
                for (PurchaseLineDraft line : draft.lines()) {
                    PartDetails part = parts.findDetailsById(line.partId());
                    long lineTotal = Math.multiplyExact((long) line.quantity(), line.unitCostPaise());
                    total = Math.addExact(total, lineTotal);
                    prepared.add(new PreparedPurchaseLine(part, line, lineTotal));
                }
            } catch (ArithmeticException ex) {
                throw new IllegalArgumentException("Purchase order amount is too large.", ex);
            }
            String orderNumber = "ORD-" + LocalDate.now(clock).toString().replace("-", "")
                    + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
            KeyHolder key = new GeneratedKeyHolder();
            long orderTotal = total;
            jdbc.update((PreparedStatementCreator) connection -> {
                PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO purchase_orders(order_number,supplier_id,supplier_name_snapshot,status,
                                                expected_date,notes,subtotal_paise)
                    VALUES (?,?,?,'ORDERED',?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, orderNumber);
                ps.setLong(2, supplier.id());
                ps.setString(3, supplier.name());
                ps.setString(4, draft.expectedDate());
                ps.setString(5, draft.notes());
                ps.setLong(6, orderTotal);
                return ps;
            }, key);
            Number generated = key.getKey();
            if (generated == null) throw new IllegalStateException("Database did not return the purchase order ID.");
            long orderId = generated.longValue();
            for (PreparedPurchaseLine item : prepared) {
                jdbc.update("""
                    INSERT INTO purchase_order_items(purchase_order_id,part_id,sku_snapshot,name_snapshot,
                                                     quantity,unit_cost_paise,line_total_paise)
                    VALUES (?,?,?,?,?,?,?)
                    """, orderId, item.part().id(), item.part().sku(), item.part().name(),
                        item.draft().quantity(), item.draft().unitCostPaise(), item.lineTotalPaise());
            }
            return purchaseOrder(orderNumber);
        });
    }

    /** Lists formal purchase orders, newest first, with a bounded search. */
    public List<FormalPurchaseOrderRow> purchaseOrders(String rawQuery, int requestedLimit) {
        String query = rawQuery == null ? "" : rawQuery.strip().replaceAll("\\s+", " ");
        if (query.length() > 120) throw new IllegalArgumentException("Purchase order search cannot exceed 120 characters.");
        int limit = Math.max(1, Math.min(requestedLimit, 500));
        String pattern = "%" + escapeLike(query) + "%";
        return jdbc.query("""
            SELECT o.id,o.order_number,o.supplier_name_snapshot,
                   CASE WHEN o.status='ORDERED' AND EXISTS (
                     SELECT 1 FROM purchase_order_items partial_line
                     WHERE partial_line.purchase_order_id=o.id AND partial_line.received_quantity>0
                   ) THEN 'PARTIALLY_RECEIVED' ELSE o.status END AS status,o.created_at,
                   COALESCE(o.expected_date,'') AS expected_date,COALESCE(o.notes,'') AS notes,
                   COALESCE((SELECT group_concat(i.sku_snapshot || ' x' || i.quantity || ' (received ' || i.received_quantity || ')', ', ')
                             FROM purchase_order_items i WHERE i.purchase_order_id=o.id),'') AS item_summary,
                   (SELECT COUNT(*) FROM purchase_order_items i WHERE i.purchase_order_id=o.id) AS line_count,
                   COALESCE((SELECT SUM(i.quantity) FROM purchase_order_items i WHERE i.purchase_order_id=o.id),0) AS units,
                   o.subtotal_paise,COALESCE((SELECT group_concat(receipt.purchase_number, ', ')
                       FROM purchase_order_receipts r JOIN purchases receipt ON receipt.id=r.purchase_id
                       WHERE r.purchase_order_id=o.id),'') AS receipt_number,
                   COALESCE(o.cancellation_reason,'') AS cancellation_reason
            FROM purchase_orders o
            WHERE (?='' OR o.order_number LIKE ? ESCAPE '!'
                OR o.supplier_name_snapshot LIKE ? ESCAPE '!'
                OR COALESCE(o.notes,'') LIKE ? ESCAPE '!'
                OR EXISTS (SELECT 1 FROM purchase_order_items i WHERE i.purchase_order_id=o.id
                           AND (i.sku_snapshot LIKE ? ESCAPE '!' OR i.name_snapshot LIKE ? ESCAPE '!')))
            ORDER BY o.created_at DESC,o.id DESC LIMIT ?
            """, (rs,row) -> new FormalPurchaseOrderRow(
                rs.getLong("id"),rs.getString("order_number"),rs.getString("supplier_name_snapshot"),
                rs.getString("status"),rs.getString("created_at"),rs.getString("expected_date"),
                rs.getString("notes"),rs.getString("item_summary"),rs.getInt("line_count"),
                rs.getInt("units"),rs.getLong("subtotal_paise"),rs.getString("receipt_number"),
                rs.getString("cancellation_reason")),
                query,pattern,pattern,pattern,pattern,pattern,limit);
    }

    /** Receives all remaining order quantities as one atomic stock receipt. */
    public FormalPurchaseOrderReceipt receivePurchaseOrder(String rawOrderNumber, String supplierInvoiceReference) {
        String orderNumber = cleanOrderNumber(rawOrderNumber);
        List<FormalPurchaseOrderLineRow> remaining = purchaseOrderLines(orderNumber).stream()
                .filter(line -> line.remainingQuantity() > 0).toList();
        if (remaining.isEmpty()) throw new IllegalStateException("Purchase order has no outstanding quantities.");
        List<PurchaseOrderReceiveLine> quantities = remaining.stream()
                .map(line -> new PurchaseOrderReceiveLine(line.partId(), line.remainingQuantity())).toList();
        return receivePurchaseOrder(orderNumber, supplierInvoiceReference, quantities);
    }

    /** Returns the ordered, received and outstanding quantities for an order. */
    public List<FormalPurchaseOrderLineRow> purchaseOrderLines(String rawOrderNumber) {
        String orderNumber = cleanOrderNumber(rawOrderNumber);
        List<Long> ids = jdbc.query("SELECT id FROM purchase_orders WHERE order_number=? COLLATE NOCASE",
                (rs,row) -> rs.getLong(1), orderNumber);
        if (ids.isEmpty()) throw new IllegalArgumentException("Purchase order was not found.");
        return jdbc.query("""
            SELECT i.id,i.part_id,i.sku_snapshot,i.name_snapshot,i.quantity,
                   i.received_quantity,i.quantity-i.received_quantity AS remaining_quantity,
                   i.unit_cost_paise
            FROM purchase_order_items i
            JOIN purchase_orders o ON o.id=i.purchase_order_id
            WHERE o.id=?
            ORDER BY i.id
            """, (rs,row) -> new FormalPurchaseOrderLineRow(rs.getLong("id"),rs.getLong("part_id"),
                rs.getString("sku_snapshot"),rs.getString("name_snapshot"),rs.getInt("quantity"),
                rs.getInt("received_quantity"),rs.getInt("remaining_quantity"),rs.getLong("unit_cost_paise")),
                ids.get(0));
    }

    /**
     * Posts a partial or complete receipt. Requested quantities are validated against each
     * order line's remaining quantity; receipt, stock, ledger and order-receipt links share
     * the same transaction so any failed write rolls the entire receipt back.
     */
    public FormalPurchaseOrderReceipt receivePurchaseOrder(String rawOrderNumber, String supplierInvoiceReference,
                                                            List<PurchaseOrderReceiveLine> requestedLines) {
        String orderNumber = cleanOrderNumber(rawOrderNumber);
        if (requestedLines == null || requestedLines.isEmpty()) {
            throw new IllegalArgumentException("Enter at least one quantity to receive.");
        }
        if (requestedLines.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Received order lines cannot be null.");
        }
        java.util.Map<Long,Integer> requested = new java.util.LinkedHashMap<>();
        for (PurchaseOrderReceiveLine line : requestedLines) {
            if (requested.putIfAbsent(line.partId(), line.quantity()) != null) {
                throw new IllegalArgumentException("A part can appear only once in a receipt.");
            }
        }
        return transactions.execute(status -> {
            List<FormalOrderHeader> headers = jdbc.query("""
                SELECT id,order_number,supplier_id,status FROM purchase_orders
                WHERE order_number=? COLLATE NOCASE
                """, (rs,row) -> new FormalOrderHeader(rs.getLong("id"),rs.getString("order_number"),
                    rs.getLong("supplier_id"),rs.getString("status")), orderNumber);
            if (headers.isEmpty()) throw new IllegalArgumentException("Purchase order was not found.");
            FormalOrderHeader order = headers.get(0);
            if (!"ORDERED".equals(order.status())) {
                throw new IllegalStateException("Only an open purchase order can be received. Current status: "
                        + order.status() + ".");
            }
            List<FormalPurchaseOrderLineRow> allLines = jdbc.query("""
                SELECT id,part_id,sku_snapshot,name_snapshot,quantity,received_quantity,
                       quantity-received_quantity AS remaining_quantity,unit_cost_paise
                FROM purchase_order_items WHERE purchase_order_id=? ORDER BY id
                """, (rs,row) -> new FormalPurchaseOrderLineRow(rs.getLong("id"),rs.getLong("part_id"),
                    rs.getString("sku_snapshot"),rs.getString("name_snapshot"),rs.getInt("quantity"),
                    rs.getInt("received_quantity"),rs.getInt("remaining_quantity"),rs.getLong("unit_cost_paise")),
                    order.id());
            if (allLines.isEmpty()) throw new IllegalStateException("Purchase order has no lines.");
            java.util.Map<Long,FormalPurchaseOrderLineRow> byPart = new java.util.HashMap<>();
            for (FormalPurchaseOrderLineRow line : allLines) byPart.put(line.partId(), line);
            for (Long partId : requested.keySet()) {
                if (!byPart.containsKey(partId)) {
                    throw new IllegalArgumentException("A selected part does not belong to this purchase order.");
                }
            }
            List<PurchaseLineDraft> receiptLines = new java.util.ArrayList<>();
            for (FormalPurchaseOrderLineRow line : allLines) {
                Integer quantity = requested.get(line.partId());
                if (quantity == null) continue;
                if (quantity <= 0) throw new IllegalArgumentException("Received quantities must be greater than zero.");
                if (quantity > line.remainingQuantity()) {
                    throw new IllegalArgumentException("Received quantity for " + line.sku()
                            + " exceeds the outstanding quantity of " + line.remainingQuantity() + ".");
                }
                receiptLines.add(new PurchaseLineDraft(line.partId(), quantity, line.unitCostPaise()));
            }
            if (receiptLines.isEmpty()) throw new IllegalArgumentException("Enter at least one quantity to receive.");

            PurchaseOrderReceipt receipt = receive(new PurchaseOrderDraft(order.supplierId(),
                    supplierInvoiceReference, receiptLines));
            KeyHolder receiptKey = new GeneratedKeyHolder();
            jdbc.update((PreparedStatementCreator) connection -> {
                PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO purchase_order_receipts(purchase_order_id,purchase_id)
                    VALUES (?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, order.id());
                ps.setLong(2, receipt.purchaseId());
                return ps;
            }, receiptKey);
            Number receiptGenerated = receiptKey.getKey();
            if (receiptGenerated == null) throw new IllegalStateException("Database did not return the order receipt ID.");
            long orderReceiptId = receiptGenerated.longValue();

            for (PurchaseLineDraft receivedLine : receiptLines) {
                FormalPurchaseOrderLineRow orderLine = byPart.get(receivedLine.partId());
                List<Long> purchaseItemIds = jdbc.query("""
                    SELECT id FROM purchase_items WHERE purchase_id=? AND part_id=?
                    """, (rs,row) -> rs.getLong(1), receipt.purchaseId(), receivedLine.partId());
                if (purchaseItemIds.isEmpty()) throw new IllegalStateException("Could not link a received part to its stock receipt.");
                int linked = jdbc.update("""
                    INSERT INTO purchase_order_receipt_items(
                        purchase_order_receipt_id,purchase_order_item_id,purchase_item_id,quantity)
                    VALUES (?,?,?,?)
                    """, orderReceiptId, orderLine.id(), purchaseItemIds.get(0), receivedLine.quantity());
                if (linked != 1) throw new IllegalStateException("Could not record the purchase order receipt line.");
                int updated = jdbc.update("""
                    UPDATE purchase_order_items SET received_quantity=received_quantity+?
                    WHERE id=? AND received_quantity+?<=quantity
                    """, receivedLine.quantity(), orderLine.id(), receivedLine.quantity());
                if (updated != 1) throw new IllegalStateException("Outstanding quantity changed; no receipt was committed.");
            }
            Long remainingLines = jdbc.queryForObject("""
                SELECT COUNT(*) FROM purchase_order_items
                WHERE purchase_order_id=? AND received_quantity<quantity
                """, Long.class, order.id());
            if (remainingLines != null && remainingLines == 0) {
                int marked = jdbc.update("""
                    UPDATE purchase_orders SET status='RECEIVED',
                        receipt_purchase_id=COALESCE(receipt_purchase_id,?)
                    WHERE id=? AND status='ORDERED'
                    """, receipt.purchaseId(), order.id());
                if (marked != 1) throw new IllegalStateException("Purchase order status changed; receipt was rolled back.");
            }
            return new FormalPurchaseOrderReceipt(order.orderNumber(),receipt.purchaseNumber(),
                    receipt.lineCount(),receipt.units(),receipt.totalPaise());
        });
    }

    /** Cancels an order that has not yet been received; stock is not changed. */
    public FormalPurchaseOrderRow cancelPurchaseOrder(String rawOrderNumber, String rawReason) {
        String orderNumber = cleanOrderNumber(rawOrderNumber);
        String reason = rawReason == null ? "" : rawReason.strip().replaceAll("\\s+", " ");
        if (reason.isEmpty()) throw new IllegalArgumentException("A purchase order cancellation reason is required.");
        if (reason.length() > 240) throw new IllegalArgumentException("Cancellation reason cannot exceed 240 characters.");
        return transactions.execute(status -> {
            int changed = jdbc.update("""
                UPDATE purchase_orders SET status='CANCELLED',cancellation_reason=?,cancelled_at=CURRENT_TIMESTAMP
                WHERE order_number=? COLLATE NOCASE AND status='ORDERED'
                  AND NOT EXISTS (SELECT 1 FROM purchase_order_items i
                                  WHERE i.purchase_order_id=purchase_orders.id AND i.received_quantity>0)
                """, reason, orderNumber);
            if (changed != 1) {
                List<String> states = jdbc.query("""
                        SELECT CASE WHEN o.status='ORDERED' AND EXISTS (
                          SELECT 1 FROM purchase_order_items i WHERE i.purchase_order_id=o.id AND i.received_quantity>0
                        ) THEN 'PARTIALLY_RECEIVED' ELSE o.status END
                        FROM purchase_orders o WHERE o.order_number=? COLLATE NOCASE
                        """,
                        (rs,row) -> rs.getString(1), orderNumber);
                if (states.isEmpty()) throw new IllegalArgumentException("Purchase order was not found.");
                throw new IllegalStateException("Only an ORDERED purchase order can be cancelled. Current status: " + states.get(0) + ".");
            }
            return purchaseOrder(orderNumber);
        });
    }

    private FormalPurchaseOrderRow purchaseOrder(String rawOrderNumber) {
        String number = cleanOrderNumber(rawOrderNumber);
        List<FormalPurchaseOrderRow> rows = jdbc.query("""
            SELECT o.id,o.order_number,o.supplier_name_snapshot,
                   CASE WHEN o.status='ORDERED' AND EXISTS (
                     SELECT 1 FROM purchase_order_items partial_line
                     WHERE partial_line.purchase_order_id=o.id AND partial_line.received_quantity>0
                   ) THEN 'PARTIALLY_RECEIVED' ELSE o.status END AS status,o.created_at,
                   COALESCE(o.expected_date,'') AS expected_date,COALESCE(o.notes,'') AS notes,
                   COALESCE((SELECT group_concat(i.sku_snapshot || ' x' || i.quantity || ' (received ' || i.received_quantity || ')', ', ')
                             FROM purchase_order_items i WHERE i.purchase_order_id=o.id),'') AS item_summary,
                   (SELECT COUNT(*) FROM purchase_order_items i WHERE i.purchase_order_id=o.id) AS line_count,
                   COALESCE((SELECT SUM(i.quantity) FROM purchase_order_items i WHERE i.purchase_order_id=o.id),0) AS units,
                   o.subtotal_paise,COALESCE((SELECT group_concat(receipt.purchase_number, ', ')
                       FROM purchase_order_receipts r JOIN purchases receipt ON receipt.id=r.purchase_id
                       WHERE r.purchase_order_id=o.id),'') AS receipt_number,
                   COALESCE(o.cancellation_reason,'') AS cancellation_reason
            FROM purchase_orders o
            WHERE o.order_number=? COLLATE NOCASE
            """, (rs,row) -> new FormalPurchaseOrderRow(
                rs.getLong("id"),rs.getString("order_number"),rs.getString("supplier_name_snapshot"),
                rs.getString("status"),rs.getString("created_at"),rs.getString("expected_date"),
                rs.getString("notes"),rs.getString("item_summary"),rs.getInt("line_count"),
                rs.getInt("units"),rs.getLong("subtotal_paise"),rs.getString("receipt_number"),
                rs.getString("cancellation_reason")), number);
        if (rows.isEmpty()) throw new IllegalArgumentException("Purchase order was not found.");
        return rows.get(0);
    }

    private static String cleanOrderNumber(String raw) {
        String number = raw == null ? "" : raw.strip();
        if (number.isEmpty() || number.length() > 64) throw new IllegalArgumentException("Enter a valid purchase order number.");
        return number;
    }

    private record FormalOrderHeader(long id, String orderNumber, long supplierId, String status) { }


    /** Lists the remaining returnable quantity for each line on a posted supplier receipt. */
    public List<SupplierReturnableLine> returnablePurchaseLines(String rawPurchaseNumber) {
        String number = cleanPurchaseNumber(rawPurchaseNumber);
        List<String> statuses = jdbc.query("SELECT status FROM purchases WHERE purchase_number=? COLLATE NOCASE",
                (rs,row) -> rs.getString(1), number);
        if (statuses.isEmpty()) throw new IllegalArgumentException("Supplier receipt was not found.");
        if (!"RECEIVED".equals(statuses.get(0))) {
            throw new IllegalStateException("Only a RECEIVED supplier receipt can have a return.");
        }
        return jdbc.query("""
            SELECT pi.id AS purchase_item_id,pi.part_id,pi.sku_snapshot,pi.name_snapshot,pi.quantity,
                   pi.unit_cost_paise,
                   COALESCE((SELECT SUM(ri.quantity) FROM supplier_return_items ri
                             JOIN supplier_returns r ON r.id=ri.supplier_return_id
                             WHERE ri.purchase_item_id=pi.id AND r.status='POSTED'),0) AS returned_quantity,
                   MAX(0,COALESCE(inv.quantity_on_hand,0)-COALESCE(inv.reserved_quantity,0)) AS available_quantity
            FROM purchase_items pi
            JOIN purchases p ON p.id=pi.purchase_id
            LEFT JOIN inventory inv ON inv.part_id=pi.part_id
            WHERE p.purchase_number=? COLLATE NOCASE
            ORDER BY pi.id
            """, (rs,row) -> {
                int received = rs.getInt("quantity");
                int returned = rs.getInt("returned_quantity");
                return new SupplierReturnableLine(rs.getLong("purchase_item_id"),rs.getLong("part_id"),
                    rs.getString("sku_snapshot"),rs.getString("name_snapshot"),received,returned,
                    Math.max(0,received-returned),rs.getInt("available_quantity"),rs.getLong("unit_cost_paise"));
            }, number);
    }

    /** Posts an immutable supplier return/credit note and reduces stock and payable atomically. */
    public SupplierReturnReceipt recordSupplierReturn(SupplierReturnDraft draft) {
        if (draft == null) throw new IllegalArgumentException("Supplier return details are required.");
        String number = cleanPurchaseNumber(draft.purchaseNumber());
        return transactions.execute(status -> {
            List<PurchaseHeader> headers = jdbc.query("""
                SELECT id,purchase_number,status FROM purchases
                WHERE purchase_number=? COLLATE NOCASE
                """, (rs,row) -> new PurchaseHeader(rs.getLong("id"),rs.getString("purchase_number"),rs.getString("status")),
                number);
            if (headers.isEmpty()) throw new IllegalArgumentException("Supplier receipt was not found.");
            PurchaseHeader purchase = headers.get(0);
            if (!"RECEIVED".equals(purchase.status())) {
                throw new IllegalStateException("Only a RECEIVED supplier receipt can have a return.");
            }
            Long supplierId = jdbc.queryForObject("SELECT supplier_id FROM purchases WHERE id=?",Long.class,purchase.id());
            if (supplierId == null) throw new IllegalStateException("Supplier receipt has no supplier.");
            java.util.Map<Long,SupplierReturnLineDraft> requested = new java.util.LinkedHashMap<>();
            for (SupplierReturnLineDraft line : draft.lines()) {
                if (requested.putIfAbsent(line.purchaseItemId(),line) != null) {
                    throw new IllegalArgumentException("A received line can appear only once per return.");
                }
            }
            List<SupplierReturnableLine> returnable = returnablePurchaseLines(purchase.purchaseNumber());
            java.util.Map<Long,SupplierReturnableLine> byId = new java.util.HashMap<>();
            for (SupplierReturnableLine line : returnable) byId.put(line.purchaseItemId(),line);
            for (Long itemId : requested.keySet()) {
                if (!byId.containsKey(itemId)) {
                    throw new IllegalArgumentException("A selected line does not belong to this supplier receipt.");
                }
            }
            long total = 0;
            int units = 0;
            for (SupplierReturnLineDraft request : requested.values()) {
                SupplierReturnableLine line = byId.get(request.purchaseItemId());
                if (request.quantity() > line.returnableQuantity()) {
                    throw new IllegalArgumentException("Return quantity for " + line.sku()
                            + " exceeds the remaining returnable quantity of " + line.returnableQuantity() + ".");
                }
                if (request.quantity() > line.availableQuantity()) {
                    throw new IllegalStateException("Cannot return " + line.sku() + ": only "
                            + line.availableQuantity() + " unreserved unit(s) are available in stock.");
                }
                total = Math.addExact(total,Math.multiplyExact((long)request.quantity(),line.unitCostPaise()));
                units = Math.addExact(units,request.quantity());
            }
            String returnNumber = "RET-" + UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(java.util.Locale.ROOT);
            KeyHolder key = new GeneratedKeyHolder();
            long returnTotal = total;
            jdbc.update((PreparedStatementCreator) connection -> {
                PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO supplier_returns(return_number,purchase_id,supplier_id,reason,credit_note_reference,total_paise,status)
                    VALUES (?,?,?,?,?,?,'POSTED')
                    """,Statement.RETURN_GENERATED_KEYS);
                ps.setString(1,returnNumber);
                ps.setLong(2,purchase.id());
                ps.setLong(3,supplierId);
                ps.setString(4,draft.reason());
                ps.setString(5,draft.creditNoteReference());
                ps.setLong(6,returnTotal);
                return ps;
            },key);
            Number generated = key.getKey();
            if (generated == null) throw new IllegalStateException("Database did not return the supplier return ID.");
            long returnId = generated.longValue();
            for (SupplierReturnLineDraft request : requested.values()) {
                SupplierReturnableLine line = byId.get(request.purchaseItemId());
                int changed = jdbc.update("""
                    UPDATE inventory SET quantity_on_hand=quantity_on_hand-?
                    WHERE part_id=? AND quantity_on_hand-? >= reserved_quantity
                    """,request.quantity(),line.partId(),request.quantity());
                if (changed != 1) throw new IllegalStateException("Available stock changed for " + line.sku() + "; no return was committed.");
                long lineTotal = Math.multiplyExact((long)request.quantity(),line.unitCostPaise());
                jdbc.update("""
                    INSERT INTO supplier_return_items(supplier_return_id,purchase_item_id,part_id,
                        sku_snapshot,name_snapshot,quantity,unit_cost_paise,line_total_paise)
                    VALUES (?,?,?,?,?,?,?,?)
                    """,returnId,line.purchaseItemId(),line.partId(),line.sku(),line.name(),
                    request.quantity(),line.unitCostPaise(),lineTotal);
                jdbc.update("""
                    INSERT INTO stock_movements(part_id,movement_type,quantity,reference,reason)
                    VALUES (?,'SUPPLIER_RETURN',?,?,?)
                    """,line.partId(),-request.quantity(),returnNumber,
                    "Supplier return against " + purchase.purchaseNumber() + ": " + draft.reason());
            }
            PurchaseBalance updated = balance(purchase.purchaseNumber());
            List<String> supplierNames = jdbc.query("SELECT supplier_name_snapshot FROM purchases WHERE id=?",
                    (rs,row) -> rs.getString(1),purchase.id());
            return new SupplierReturnReceipt(returnNumber,purchase.purchaseNumber(),
                    supplierNames.isEmpty()?"":supplierNames.get(0),draft.reason(),draft.creditNoteReference(),
                    requested.size(),units,total,updated.outstandingPaise());
        });
    }

    public List<SupplierReturnRow> supplierReturns(String rawPurchaseNumber) {
        String number = cleanPurchaseNumber(rawPurchaseNumber);
        return jdbc.query("""
            SELECT r.return_number,p.purchase_number,p.supplier_name_snapshot,r.reason,
                   COALESCE(r.credit_note_reference,'') AS credit_note_reference,r.created_at,
                   (SELECT COUNT(*) FROM supplier_return_items i WHERE i.supplier_return_id=r.id) AS line_count,
                   (SELECT SUM(i.quantity) FROM supplier_return_items i WHERE i.supplier_return_id=r.id) AS units,
                   r.total_paise
            FROM supplier_returns r JOIN purchases p ON p.id=r.purchase_id
            WHERE p.purchase_number=? COLLATE NOCASE
            ORDER BY r.id DESC LIMIT 200
            """,(rs,row) -> new SupplierReturnRow(rs.getString("return_number"),rs.getString("purchase_number"),
                rs.getString("supplier_name_snapshot"),rs.getString("reason"),rs.getString("credit_note_reference"),
                rs.getString("created_at"),rs.getInt("line_count"),rs.getInt("units"),rs.getLong("total_paise")),number);
    }

    public PurchaseBalance balance(String rawPurchaseNumber) {
        String number = cleanPurchaseNumber(rawPurchaseNumber);
        List<PurchaseBalance> balances = jdbc.query("""
            SELECT p.purchase_number,p.supplier_name_snapshot,p.status,p.subtotal_paise,
                   COALESCE(SUM(CASE WHEN sp.status='POSTED' THEN sp.amount_paise ELSE 0 END),0) AS paid_paise,
                   COALESCE((SELECT SUM(sr.total_paise) FROM supplier_returns sr
                             WHERE sr.purchase_id=p.id AND sr.status='POSTED'),0) AS returned_paise
            FROM purchases p LEFT JOIN supplier_payments sp ON sp.purchase_id=p.id
            WHERE p.purchase_number=? COLLATE NOCASE
            GROUP BY p.id
            """, (rs,row) -> {
                long total=Math.subtractExact(rs.getLong("subtotal_paise"),rs.getLong("returned_paise"));
                long paid=rs.getLong("paid_paise");
                return new PurchaseBalance(rs.getString("purchase_number"),rs.getString("supplier_name_snapshot"),
                        rs.getString("status"),total,paid,Math.subtractExact(total,paid));
            }, number);
        if (balances.isEmpty()) throw new IllegalArgumentException("Purchase receipt was not found.");
        return balances.get(0);
    }

    public List<SupplierPaymentRow> supplierPayments(String rawPurchaseNumber) {
        String number = cleanPurchaseNumber(rawPurchaseNumber);
        return jdbc.query("""
            SELECT sp.id,p.purchase_number,p.supplier_name_snapshot,sp.method,sp.amount_paise,
                   COALESCE(sp.reference,'') AS reference,sp.status,sp.void_reason,sp.created_at
            FROM supplier_payments sp JOIN purchases p ON p.id=sp.purchase_id
            WHERE p.purchase_number=? COLLATE NOCASE
            ORDER BY sp.id DESC LIMIT 200
            """, (rs,row) -> new SupplierPaymentRow(rs.getLong("id"),rs.getString("purchase_number"),
                rs.getString("supplier_name_snapshot"),rs.getString("method"),rs.getLong("amount_paise"),
                rs.getString("reference"),rs.getString("status"),rs.getString("void_reason"),rs.getString("created_at")), number);
    }

    public SupplierPaymentReceipt recordSupplierPayment(SupplierPaymentDraft draft) {
        return transactions.execute(status -> {
            PurchaseBalance balance = balance(draft.purchaseNumber());
            if (!"RECEIVED".equals(balance.status())) {
                throw new IllegalStateException("Payments can only be recorded against a RECEIVED purchase.");
            }
            if (balance.outstandingPaise() <= 0) {
                throw new IllegalStateException("No payable balance remains for this receipt. Review supplier credits before recording another payment.");
            }
            if (draft.amountPaise() > balance.outstandingPaise()) {
                throw new IllegalArgumentException("Payment exceeds outstanding balance of ₹"
                        + BigDecimal.valueOf(balance.outstandingPaise(), 2).toPlainString() + ".");
            }
            Long purchaseId = jdbc.queryForObject(
                    "SELECT id FROM purchases WHERE purchase_number=? COLLATE NOCASE", Long.class, balance.purchaseNumber());
            KeyHolder key = new GeneratedKeyHolder();
            jdbc.update((PreparedStatementCreator) connection -> {
                PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO supplier_payments(purchase_id,method,amount_paise,reference,status)
                    VALUES (?,?,?,?,'POSTED')
                    """, Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1,purchaseId);
                ps.setString(2,draft.method().name());
                ps.setLong(3,draft.amountPaise());
                ps.setString(4,draft.reference());
                return ps;
            }, key);
            Number generated=key.getKey();
            if (generated==null) throw new IllegalStateException("Database did not return the supplier payment ID.");
            PurchaseBalance updated=balance(balance.purchaseNumber());
            return new SupplierPaymentReceipt(generated.longValue(),balance.purchaseNumber(),draft.method().name(),
                    draft.amountPaise(),updated.paidPaise(),updated.outstandingPaise(),draft.reference());
        });
    }

    public SupplierPaymentReceipt voidSupplierPayment(long paymentId, String rawReason) {
        String reason=rawReason==null?"":rawReason.strip().replaceAll("\\s+"," ");
        if (paymentId<=0) throw new IllegalArgumentException("Select a supplier payment.");
        if (reason.isEmpty()) throw new IllegalArgumentException("A reason is required to void a payment record.");
        if (reason.length()>240) throw new IllegalArgumentException("Payment void reason cannot exceed 240 characters.");
        return transactions.execute(status -> {
            List<SupplierPaymentRow> rows=jdbc.query("""
                SELECT sp.id,p.purchase_number,p.supplier_name_snapshot,sp.method,sp.amount_paise,
                       COALESCE(sp.reference,'') AS reference,sp.status,sp.void_reason,sp.created_at
                FROM supplier_payments sp JOIN purchases p ON p.id=sp.purchase_id WHERE sp.id=?
                """,(rs,row)->new SupplierPaymentRow(rs.getLong("id"),rs.getString("purchase_number"),
                    rs.getString("supplier_name_snapshot"),rs.getString("method"),rs.getLong("amount_paise"),
                    rs.getString("reference"),rs.getString("status"),rs.getString("void_reason"),rs.getString("created_at")),paymentId);
            if(rows.isEmpty()) throw new IllegalArgumentException("Supplier payment was not found.");
            SupplierPaymentRow payment=rows.get(0);
            if(!"POSTED".equals(payment.status())) throw new IllegalStateException("This payment record is already voided.");
            int changed=jdbc.update("UPDATE supplier_payments SET status='VOIDED',void_reason=?,voided_at=CURRENT_TIMESTAMP WHERE id=? AND status='POSTED'",reason,paymentId);
            if(changed!=1) throw new IllegalStateException("Payment status changed; reload and try again.");
            PurchaseBalance updated=balance(payment.purchaseNumber());
            return new SupplierPaymentReceipt(payment.id(),payment.purchaseNumber(),payment.method(),
                    payment.amountPaise(),updated.paidPaise(),updated.outstandingPaise(),payment.reference());
        });
    }

    private static String cleanPurchaseNumber(String raw) {
        String number=raw==null?"":raw.strip();
        if(number.isEmpty()||number.length()>64) throw new IllegalArgumentException("Enter a valid purchase receipt number.");
        return number;
    }

    private record PreparedPurchaseLine(PartDetails part, PurchaseLineDraft draft, long lineTotalPaise) { }
}
