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


CREATE TABLE IF NOT EXISTS part_fitments (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  part_id INTEGER NOT NULL REFERENCES parts(id),
  make TEXT NOT NULL COLLATE NOCASE,
  model TEXT NOT NULL COLLATE NOCASE,
  year_from INTEGER NOT NULL CHECK(year_from >= 1886 AND year_from <= 2100),
  year_to INTEGER NOT NULL CHECK(year_to >= year_from AND year_to <= 2100),
  engine TEXT,
  trim TEXT,
  notes TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_fitments_make_model_year
  ON part_fitments(make COLLATE NOCASE, model COLLATE NOCASE, year_from, year_to);
CREATE INDEX IF NOT EXISTS idx_fitments_part
  ON part_fitments(part_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_fitments_unique_compatibility
  ON part_fitments(
    part_id, make COLLATE NOCASE, model COLLATE NOCASE, year_from, year_to,
    COALESCE(engine, '') COLLATE NOCASE, COALESCE(trim, '') COLLATE NOCASE
  );

CREATE TABLE IF NOT EXISTS saved_views (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL COLLATE NOCASE UNIQUE,
  query TEXT,
  brand TEXT,
  material TEXT,
  color TEXT,
  category_id INTEGER REFERENCES categories(id) ON DELETE SET NULL,
  supplier_id INTEGER REFERENCES suppliers(id) ON DELETE SET NULL,
  stock_status TEXT NOT NULL DEFAULT 'ANY' CHECK(stock_status IN ('ANY','IN_STOCK','LOW_STOCK','OUT_OF_STOCK')),
  min_quantity INTEGER,
  max_quantity INTEGER,
  min_selling_price_paise INTEGER,
  max_selling_price_paise INTEGER,
  sort_by TEXT NOT NULL DEFAULT 'name',
  ascending INTEGER NOT NULL DEFAULT 1 CHECK(ascending IN (0,1)),
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_saved_views_name ON saved_views(name COLLATE NOCASE);
