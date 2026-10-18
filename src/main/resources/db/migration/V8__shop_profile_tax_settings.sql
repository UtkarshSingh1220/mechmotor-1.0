CREATE TABLE IF NOT EXISTS shop_profile (
  id INTEGER PRIMARY KEY CHECK(id=1),
  shop_name TEXT NOT NULL DEFAULT 'MechMotor Vehicle Parts Shop' CHECK(length(trim(shop_name)) BETWEEN 1 AND 120),
  address TEXT NOT NULL DEFAULT '',
  phone TEXT NOT NULL DEFAULT '',
  email TEXT NOT NULL DEFAULT '',
  gstin TEXT NOT NULL DEFAULT '',
  state_code TEXT NOT NULL DEFAULT '',
  tax_label TEXT NOT NULL DEFAULT 'GST',
  default_tax_rate_bps INTEGER NOT NULL DEFAULT 0 CHECK(default_tax_rate_bps BETWEEN 0 AND 10000),
  tax_enabled INTEGER NOT NULL DEFAULT 0 CHECK(tax_enabled IN (0,1)),
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT OR IGNORE INTO shop_profile(id) VALUES (1);
