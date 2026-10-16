CREATE TABLE IF NOT EXISTS supplier_returns (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  return_number TEXT NOT NULL UNIQUE,
  purchase_id INTEGER NOT NULL REFERENCES purchases(id),
  supplier_id INTEGER NOT NULL REFERENCES suppliers(id),
  reason TEXT NOT NULL CHECK(length(trim(reason)) BETWEEN 1 AND 240),
  credit_note_reference TEXT,
  total_paise INTEGER NOT NULL CHECK(total_paise >= 0),
  status TEXT NOT NULL DEFAULT 'POSTED' CHECK(status='POSTED'),
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_supplier_returns_purchase
  ON supplier_returns(purchase_id, status, id);

CREATE TABLE IF NOT EXISTS supplier_return_items (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  supplier_return_id INTEGER NOT NULL REFERENCES supplier_returns(id),
  purchase_item_id INTEGER NOT NULL REFERENCES purchase_items(id),
  part_id INTEGER NOT NULL REFERENCES parts(id),
  sku_snapshot TEXT NOT NULL,
  name_snapshot TEXT NOT NULL,
  quantity INTEGER NOT NULL CHECK(quantity > 0),
  unit_cost_paise INTEGER NOT NULL CHECK(unit_cost_paise >= 0),
  line_total_paise INTEGER NOT NULL CHECK(line_total_paise >= 0),
  UNIQUE(supplier_return_id, purchase_item_id)
);
CREATE INDEX IF NOT EXISTS idx_supplier_return_items_purchase_item
  ON supplier_return_items(purchase_item_id, supplier_return_id);
