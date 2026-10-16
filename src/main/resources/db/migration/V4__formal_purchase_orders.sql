CREATE TABLE IF NOT EXISTS purchase_orders (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  order_number TEXT NOT NULL UNIQUE,
  supplier_id INTEGER NOT NULL REFERENCES suppliers(id),
  supplier_name_snapshot TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'ORDERED' CHECK(status IN ('ORDERED','RECEIVED','CANCELLED')),
  expected_date TEXT,
  notes TEXT,
  subtotal_paise INTEGER NOT NULL CHECK(subtotal_paise >= 0),
  receipt_purchase_id INTEGER REFERENCES purchases(id),
  cancellation_reason TEXT,
  cancelled_at TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CHECK((status='CANCELLED' AND length(trim(cancellation_reason))>0 AND cancelled_at IS NOT NULL)
     OR (status<>'CANCELLED' AND cancellation_reason IS NULL AND cancelled_at IS NULL))
);
CREATE INDEX IF NOT EXISTS idx_purchase_orders_status_created
  ON purchase_orders(status, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_purchase_orders_supplier
  ON purchase_orders(supplier_id, created_at DESC);

CREATE TABLE IF NOT EXISTS purchase_order_items (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  purchase_order_id INTEGER NOT NULL REFERENCES purchase_orders(id),
  part_id INTEGER NOT NULL REFERENCES parts(id),
  sku_snapshot TEXT NOT NULL,
  name_snapshot TEXT NOT NULL,
  quantity INTEGER NOT NULL CHECK(quantity > 0),
  unit_cost_paise INTEGER NOT NULL CHECK(unit_cost_paise >= 0),
  line_total_paise INTEGER NOT NULL CHECK(line_total_paise >= 0),
  UNIQUE(purchase_order_id, part_id)
);
CREATE INDEX IF NOT EXISTS idx_purchase_order_items_order
  ON purchase_order_items(purchase_order_id, id);
