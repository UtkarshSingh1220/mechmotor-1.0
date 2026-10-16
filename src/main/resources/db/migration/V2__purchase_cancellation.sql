ALTER TABLE purchase_items
    ADD COLUMN previous_purchase_price_paise INTEGER
    CHECK(previous_purchase_price_paise IS NULL OR previous_purchase_price_paise >= 0);

CREATE TABLE IF NOT EXISTS purchase_cancellations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    purchase_id INTEGER NOT NULL UNIQUE REFERENCES purchases(id),
    reason TEXT NOT NULL CHECK(length(trim(reason)) > 0),
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_purchase_cancellations_created_at
    ON purchase_cancellations(created_at);
