-- Extend the constrained movement vocabulary without losing existing ledger history.
-- Rebuild is required because SQLite cannot alter a CHECK constraint in place.
DROP TRIGGER IF EXISTS trg_stock_movements_legacy_created_at;

ALTER TABLE stock_movements RENAME TO stock_movements_v6;

CREATE TABLE stock_movements (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  part_id INTEGER NOT NULL REFERENCES parts(id),
  movement_type TEXT NOT NULL CHECK(movement_type IN ('PURCHASE','SALE','RETURN','SUPPLIER_RETURN','ADJUSTMENT','REVERSAL')),
  quantity INTEGER NOT NULL,
  reference TEXT,
  reason TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO stock_movements(id, part_id, movement_type, quantity, reference, reason, created_at)
SELECT id, part_id, movement_type, quantity, reference, reason,
       COALESCE(NULLIF(created_at, ''), CURRENT_TIMESTAMP)
FROM stock_movements_v6;

DROP TABLE stock_movements_v6;

