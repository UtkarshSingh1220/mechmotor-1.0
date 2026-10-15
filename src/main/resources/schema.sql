PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS categories (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL COLLATE NOCASE UNIQUE,
  parent_id INTEGER REFERENCES categories(id),
  active INTEGER NOT NULL DEFAULT 1 CHECK(active IN (0,1))
);

CREATE TABLE IF NOT EXISTS suppliers (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL COLLATE NOCASE,
  phone TEXT,
  supplier_code TEXT UNIQUE
);

CREATE TABLE IF NOT EXISTS parts (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  sku TEXT NOT NULL COLLATE NOCASE UNIQUE,
  barcode TEXT UNIQUE,
  part_number TEXT,
  name TEXT NOT NULL,
  brand TEXT,
  company TEXT,
  material TEXT,
  color TEXT,
  category_id INTEGER REFERENCES categories(id),
  supplier_id INTEGER REFERENCES suppliers(id),
  purchase_price_paise INTEGER NOT NULL DEFAULT 0 CHECK(purchase_price_paise >= 0),
  selling_price_paise INTEGER NOT NULL DEFAULT 0 CHECK(selling_price_paise >= 0),
  active INTEGER NOT NULL DEFAULT 1 CHECK(active IN (0,1)),
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS inventory (
  part_id INTEGER PRIMARY KEY REFERENCES parts(id),
  quantity_on_hand INTEGER NOT NULL DEFAULT 0 CHECK(quantity_on_hand >= 0),
  reserved_quantity INTEGER NOT NULL DEFAULT 0 CHECK(reserved_quantity >= 0 AND reserved_quantity <= quantity_on_hand),
  reorder_level INTEGER NOT NULL DEFAULT 0 CHECK(reorder_level >= 0)
);

CREATE TABLE IF NOT EXISTS stock_movements (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  part_id INTEGER NOT NULL REFERENCES parts(id),
  movement_type TEXT NOT NULL CHECK(movement_type IN ('PURCHASE','SALE','RETURN','ADJUSTMENT','REVERSAL')),
  quantity INTEGER NOT NULL,
  reference TEXT,
  reason TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_parts_name ON parts(name COLLATE NOCASE);
CREATE INDEX IF NOT EXISTS idx_parts_brand ON parts(brand COLLATE NOCASE);
CREATE INDEX IF NOT EXISTS idx_parts_material ON parts(material COLLATE NOCASE);
CREATE INDEX IF NOT EXISTS idx_parts_color ON parts(color COLLATE NOCASE);
CREATE INDEX IF NOT EXISTS idx_parts_part_number ON parts(part_number COLLATE NOCASE);
CREATE INDEX IF NOT EXISTS idx_parts_updated ON parts(updated_at);
