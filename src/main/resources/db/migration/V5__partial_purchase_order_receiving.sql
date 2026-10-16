ALTER TABLE purchase_order_items
  ADD COLUMN received_quantity INTEGER NOT NULL DEFAULT 0
  CHECK(received_quantity >= 0 AND received_quantity <= quantity);

CREATE TABLE IF NOT EXISTS purchase_order_receipts (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  purchase_order_id INTEGER NOT NULL REFERENCES purchase_orders(id),
  purchase_id INTEGER NOT NULL UNIQUE REFERENCES purchases(id),
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(purchase_order_id, purchase_id)
);
CREATE INDEX IF NOT EXISTS idx_purchase_order_receipts_order
  ON purchase_order_receipts(purchase_order_id, id);

CREATE TABLE IF NOT EXISTS purchase_order_receipt_items (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  purchase_order_receipt_id INTEGER NOT NULL REFERENCES purchase_order_receipts(id),
  purchase_order_item_id INTEGER NOT NULL REFERENCES purchase_order_items(id),
  purchase_item_id INTEGER NOT NULL UNIQUE REFERENCES purchase_items(id),
  quantity INTEGER NOT NULL CHECK(quantity > 0),
  UNIQUE(purchase_order_receipt_id, purchase_order_item_id)
);

-- Preserve the receipt relationship for orders received before partial receiving existed.
INSERT INTO purchase_order_receipts(purchase_order_id, purchase_id)
SELECT id, receipt_purchase_id
FROM purchase_orders
WHERE receipt_purchase_id IS NOT NULL
  AND EXISTS (SELECT 1 FROM purchases p WHERE p.id=receipt_purchase_id);

INSERT INTO purchase_order_receipt_items(
  purchase_order_receipt_id, purchase_order_item_id, purchase_item_id, quantity
)
SELECT r.id, oi.id, pi.id, pi.quantity
FROM purchase_order_receipts r
JOIN purchase_order_items oi ON oi.purchase_order_id=r.purchase_order_id
JOIN purchase_items pi ON pi.purchase_id=r.purchase_id AND pi.part_id=oi.part_id;

UPDATE purchase_order_items
SET received_quantity = COALESCE((
  SELECT SUM(ri.quantity)
  FROM purchase_order_receipt_items ri
  WHERE ri.purchase_order_item_id=purchase_order_items.id
), 0);
