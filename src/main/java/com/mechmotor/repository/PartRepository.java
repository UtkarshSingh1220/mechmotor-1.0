package com.mechmotor.repository;

import com.mechmotor.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Repository
public class PartRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<Part> PART_MAPPER = (rs, row) -> new Part(
            rs.getLong("id"), rs.getString("sku"), rs.getString("barcode"),
            rs.getString("part_number"), rs.getString("name"), rs.getString("brand"),
            rs.getString("company"), rs.getString("material"), rs.getString("color"),
            rs.getString("category"), rs.getString("supplier"), rs.getInt("quantity"),
            rs.getLong("purchase_price_paise"), rs.getLong("selling_price_paise"), rs.getInt("reorder_level"));

    public PartRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Part> search(String rawQuery) {
        return findPage(InventoryFilter.defaults(rawQuery)).items();
    }

    public InventoryPage findPage(InventoryFilter filter) {
        Query query = buildQuery(filter);
        Long total = jdbc.queryForObject(query.countSql(), Long.class, query.args().toArray());
        List<Object> pageArgs = new ArrayList<>(query.selectArgs());
        pageArgs.add(filter.pageSize());
        pageArgs.add((long) filter.page() * filter.pageSize());
        List<Part> items = jdbc.query(query.selectSql(), PART_MAPPER, pageArgs.toArray());
        return new InventoryPage(items, total == null ? 0 : total, filter.page(), filter.pageSize());
    }

    public List<LookupOption> categories() {
        return jdbc.query("""
            WITH RECURSIVE category_tree(id, name, parent_id, depth, path) AS (
                SELECT id, name, parent_id, 0, name
                FROM categories WHERE active=1 AND parent_id IS NULL
                UNION ALL
                SELECT c.id, c.name, c.parent_id, t.depth+1, t.path || ' / ' || c.name
                FROM categories c JOIN category_tree t ON c.parent_id=t.id
                WHERE c.active=1 AND t.depth < 20
            )
            SELECT id, path AS name FROM category_tree ORDER BY path COLLATE NOCASE
            """, (rs, row) -> new LookupOption(rs.getLong("id"), rs.getString("name")));
    }

    public List<LookupOption> suppliers() {
        return jdbc.query("SELECT id, name FROM suppliers ORDER BY name COLLATE NOCASE",
                (rs, row) -> new LookupOption(rs.getLong("id"), rs.getString("name")));
    }

    public List<LookupOption> savedViews() {
        return jdbc.query("SELECT id,name FROM saved_views ORDER BY name COLLATE NOCASE",
                (rs, row) -> new LookupOption(rs.getLong("id"), rs.getString("name")));
    }

    public long saveView(String rawName, InventoryFilter filter) {
        String name = cleanRequiredName(rawName, "Saved view");
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM saved_views WHERE name=? COLLATE NOCASE", Integer.class, name);
        if (existing != null && existing > 0) {
            throw new IllegalArgumentException("A saved view with this name already exists. Delete it before saving a replacement.");
        }
        jdbc.update("""
            INSERT INTO saved_views(name,query,brand,material,color,category_id,supplier_id,stock_status,
                min_quantity,max_quantity,min_selling_price_paise,max_selling_price_paise,sort_by,ascending)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """, name, filter.query(), filter.brand(), filter.material(), filter.color(),
                filter.categoryId(), filter.supplierId(), filter.stockStatus().name(),
                filter.minQuantity(), filter.maxQuantity(), filter.minSellingPricePaise(),
                filter.maxSellingPricePaise(), filter.sortBy(), filter.ascending() ? 1 : 0);
        return jdbc.queryForObject("SELECT id FROM saved_views WHERE name=? COLLATE NOCASE", Long.class, name);
    }

    public SavedInventoryView savedView(long id) {
        List<SavedInventoryView> rows = jdbc.query("SELECT * FROM saved_views WHERE id=?", (rs, row) -> {
            InventoryFilter filter = new InventoryFilter(
                    rs.getString("query"), rs.getString("brand"), rs.getString("material"), rs.getString("color"),
                    nullableLong(rs, "category_id"), nullableLong(rs, "supplier_id"),
                    InventoryFilter.StockStatus.valueOf(rs.getString("stock_status")),
                    nullableInteger(rs, "min_quantity"), nullableInteger(rs, "max_quantity"),
                    nullableLong(rs, "min_selling_price_paise"), nullableLong(rs, "max_selling_price_paise"),
                    0, 50, rs.getString("sort_by"), rs.getInt("ascending") == 1);
            return new SavedInventoryView(rs.getLong("id"), rs.getString("name"), filter);
        }, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Saved inventory view was not found.");
        return rows.get(0);
    }

    public void deleteSavedView(long id) {
        int changed = jdbc.update("DELETE FROM saved_views WHERE id=?", id);
        if (changed == 0) throw new IllegalArgumentException("Saved inventory view was not found.");
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInteger(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    public List<VehicleFitment> fitments(long partId) {
        return jdbc.query("""
            SELECT id,part_id,make,model,year_from,year_to,engine,trim,notes
            FROM part_fitments WHERE part_id=?
            ORDER BY make COLLATE NOCASE, model COLLATE NOCASE, year_from, year_to
            """, (rs, row) -> new VehicleFitment(rs.getLong("id"), rs.getLong("part_id"),
                rs.getString("make"), rs.getString("model"), rs.getInt("year_from"),
                rs.getInt("year_to"), rs.getString("engine"), rs.getString("trim"), rs.getString("notes")), partId);
    }

    public long addFitment(long partId, VehicleFitmentDraft draft) {
        Integer activePart = jdbc.queryForObject("SELECT COUNT(*) FROM parts WHERE id=? AND active=1", Integer.class, partId);
        if (activePart == null || activePart != 1) throw new IllegalArgumentException("Choose an active inventory item.");
        Integer duplicates = jdbc.queryForObject("""
            SELECT COUNT(*) FROM part_fitments
            WHERE part_id=? AND make=? COLLATE NOCASE AND model=? COLLATE NOCASE
              AND year_from=? AND year_to=?
              AND COALESCE(engine,'')=? COLLATE NOCASE AND COALESCE(trim,'')=? COLLATE NOCASE
            """, Integer.class, partId, draft.make(), draft.model(), draft.yearFrom(), draft.yearTo(),
                nullToEmpty(draft.engine()), nullToEmpty(draft.trim()));
        if (duplicates != null && duplicates > 0) {
            throw new IllegalArgumentException("This vehicle fitment already exists for the selected part.");
        }
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update((PreparedStatementCreator) connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO part_fitments(part_id,make,model,year_from,year_to,engine,trim,notes)
                VALUES (?,?,?,?,?,?,?,?)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, partId);
            ps.setString(2, draft.make());
            ps.setString(3, draft.model());
            ps.setInt(4, draft.yearFrom());
            ps.setInt(5, draft.yearTo());
            ps.setString(6, draft.engine());
            ps.setString(7, draft.trim());
            ps.setString(8, draft.notes());
            return ps;
        }, key);
        Number generated = key.getKey();
        if (generated == null) throw new IllegalStateException("Database did not return the fitment ID.");
        return generated.longValue();
    }

    public void removeFitment(long partId, long fitmentId) {
        int changed = jdbc.update("DELETE FROM part_fitments WHERE id=? AND part_id=?", fitmentId, partId);
        if (changed == 0) throw new IllegalArgumentException("Vehicle fitment was not found for this part.");
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }

    public long createCategory(String rawName) {
        return createCategory(rawName, null);
    }

    public long createCategory(String rawName, Long parentId) {
        String name = cleanRequiredName(rawName, "Category");
        if (parentId != null) {
            Integer parentCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM categories WHERE id=? AND active=1", Integer.class, parentId);
            if (parentCount == null || parentCount != 1) {
                throw new IllegalArgumentException("Choose an active parent category.");
            }
        }
        jdbc.update("INSERT INTO categories(name, parent_id) VALUES (?, ?)", name, parentId);
        return jdbc.queryForObject("SELECT id FROM categories WHERE name=? COLLATE NOCASE", Long.class, name);
    }

    public void renameCategory(long id, String rawName) {
        String name = cleanRequiredName(rawName, "Category");
        int changed = jdbc.update("UPDATE categories SET name=? WHERE id=? AND active=1", name, id);
        if (changed == 0) throw new IllegalArgumentException("Category was not found or is inactive.");
    }

    public void deactivateCategory(long id) {
        Integer children = jdbc.queryForObject(
                "SELECT COUNT(*) FROM categories WHERE parent_id=? AND active=1", Integer.class, id);
        if (children != null && children > 0) {
            throw new IllegalArgumentException("Move or deactivate active subcategories before deactivating this category.");
        }
        Integer parts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM parts WHERE category_id=? AND active=1", Integer.class, id);
        if (parts != null && parts > 0) {
            throw new IllegalArgumentException("Reassign active inventory items before deactivating this category.");
        }
        int changed = jdbc.update("UPDATE categories SET active=0 WHERE id=? AND active=1", id);
        if (changed == 0) throw new IllegalArgumentException("Category was not found or is already inactive.");
    }

    public long createSupplier(String rawName) {
        return createSupplier(new SupplierDraft(rawName, null, null));
    }

    public long createSupplier(SupplierDraft draft) {
        String name = cleanRequiredName(draft.name(), "Supplier");
        String phone = cleanOptional(draft.phone(), 40, "Supplier phone");
        String supplierCode = cleanOptional(draft.supplierCode(), 80, "Supplier code");
        List<Long> existingIds = jdbc.query(
                "SELECT id FROM suppliers WHERE name = ? COLLATE NOCASE ORDER BY id LIMIT 1",
                (rs, row) -> rs.getLong("id"), name);
        if (!existingIds.isEmpty()) {
            long id = existingIds.get(0);
            jdbc.update("UPDATE suppliers SET phone=COALESCE(?, phone), supplier_code=COALESCE(?, supplier_code) WHERE id=?", phone, supplierCode, id);
            return id;
        }
        jdbc.update("INSERT INTO suppliers(name, phone, supplier_code) VALUES (?, ?, ?)", name, phone, supplierCode);
        return jdbc.queryForObject("SELECT id FROM suppliers WHERE name = ? COLLATE NOCASE ORDER BY id LIMIT 1", Long.class, name);
    }

    public List<SupplierDetails> supplierDetails() {
        return jdbc.query("SELECT id,name,phone,supplier_code FROM suppliers ORDER BY name COLLATE NOCASE",
                (rs, row) -> new SupplierDetails(rs.getLong("id"), rs.getString("name"), rs.getString("phone"), rs.getString("supplier_code")));
    }

    public SupplierDetails supplierDetails(long id) {
        List<SupplierDetails> rows = jdbc.query("SELECT id,name,phone,supplier_code FROM suppliers WHERE id=?",
                (rs, row) -> new SupplierDetails(rs.getLong("id"), rs.getString("name"), rs.getString("phone"), rs.getString("supplier_code")), id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Supplier was not found.");
        return rows.get(0);
    }

    public void updateSupplier(long id, SupplierDraft draft) {
        String name = cleanRequiredName(draft.name(), "Supplier");
        String phone = cleanOptional(draft.phone(), 40, "Supplier phone");
        String supplierCode = cleanOptional(draft.supplierCode(), 80, "Supplier code");
        Integer duplicateNames = jdbc.queryForObject(
                "SELECT COUNT(*) FROM suppliers WHERE name=? COLLATE NOCASE AND id<>?", Integer.class, name, id);
        if (duplicateNames != null && duplicateNames > 0) {
            throw new IllegalArgumentException("Another supplier already uses this name.");
        }
        int changed = jdbc.update("UPDATE suppliers SET name=?, phone=?, supplier_code=? WHERE id=?", name, phone, supplierCode, id);
        if (changed == 0) throw new IllegalArgumentException("Supplier was not found.");
    }

    private static String cleanOptional(String raw, int maxLength, String label) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.strip().replaceAll("\\s+", " ");
        if (value.length() > maxLength) throw new IllegalArgumentException(label + " cannot exceed " + maxLength + " characters.");
        return value;
    }

    private static String cleanRequiredName(String rawName, String label) {
        if (rawName == null || rawName.isBlank()) throw new IllegalArgumentException(label + " name is required.");
        String name = rawName.strip().replaceAll("\\s+", " ");
        if (name.length() > 160) throw new IllegalArgumentException(label + " name cannot exceed 160 characters.");
        return name;
    }

    public Part findById(long id) {
        List<Part> rows = jdbc.query(baseSelect() + " WHERE p.id = ?", PART_MAPPER, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Inventory item no longer exists.");
        return rows.get(0);
    }

    public PartDetails findDetailsById(long id) {
        List<PartDetails> rows = jdbc.query("""
            SELECT p.id,p.sku,p.barcode,p.part_number,p.name,p.brand,p.company,p.material,p.color,
                   p.category_id,p.supplier_id,p.purchase_price_paise,p.selling_price_paise,
                   COALESCE(i.quantity_on_hand,0) quantity,COALESCE(i.reorder_level,0) reorder_level
            FROM parts p LEFT JOIN inventory i ON i.part_id=p.id WHERE p.id=? AND p.active=1
            """, (rs, row) -> {
                long category = rs.getLong("category_id");
                Long categoryId = rs.wasNull() ? null : category;
                long supplier = rs.getLong("supplier_id");
                Long supplierId = rs.wasNull() ? null : supplier;
                return new PartDetails(rs.getLong("id"), rs.getString("sku"), rs.getString("barcode"),
                    rs.getString("part_number"), rs.getString("name"), rs.getString("brand"),
                    rs.getString("company"), rs.getString("material"), rs.getString("color"),
                    categoryId, supplierId, rs.getLong("purchase_price_paise"),
                    rs.getLong("selling_price_paise"), rs.getInt("quantity"), rs.getInt("reorder_level"));
            }, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Inventory item no longer exists or is inactive.");
        return rows.get(0);
    }

    public long create(PartDraft draft) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update((PreparedStatementCreator) connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO parts (sku, barcode, part_number, name, brand, company, material, color,
                    category_id, supplier_id, purchase_price_paise, selling_price_paise, active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                """, Statement.RETURN_GENERATED_KEYS);
            bindDraft(ps, draft);
            return ps;
        }, key);
        Number generated = key.getKey();
        if (generated == null) throw new IllegalStateException("Database did not return the new part ID.");
        long id = generated.longValue();
        jdbc.update("INSERT INTO inventory(part_id, quantity_on_hand, reorder_level) VALUES (?, ?, ?)",
                id, draft.quantity(), draft.reorderLevel());
        if (draft.quantity() > 0) {
            jdbc.update("INSERT INTO stock_movements(part_id, movement_type, quantity, reason) VALUES (?, 'ADJUSTMENT', ?, ?)",
                    id, draft.quantity(), "Opening inventory");
        }
        return id;
    }

    public void update(long id, PartDraft draft) {
        int changed = jdbc.update("""
            UPDATE parts SET sku=?, barcode=?, part_number=?, name=?, brand=?, company=?, material=?,
                color=?, category_id=?, supplier_id=?, purchase_price_paise=?, selling_price_paise=?,
                updated_at=CURRENT_TIMESTAMP
            WHERE id=? AND active=1
            """, draft.sku(), draft.barcode(), draft.partNumber(), draft.name(), draft.brand(),
                draft.company(), draft.material(), draft.color(), draft.categoryId(), draft.supplierId(),
                draft.purchasePricePaise(), draft.sellingPricePaise(), id);
        if (changed == 0) throw new IllegalArgumentException("Inventory item was not found or is inactive.");
        jdbc.update("""
            INSERT INTO inventory(part_id, quantity_on_hand, reorder_level) VALUES (?, ?, ?)
            ON CONFLICT(part_id) DO UPDATE SET reorder_level=excluded.reorder_level
            """, id, draft.quantity(), draft.reorderLevel());
        // Quantity changes must be auditable; never silently overwrite stock.
        Integer current = jdbc.queryForObject("SELECT quantity_on_hand FROM inventory WHERE part_id=?", Integer.class, id);
        if (current == null) throw new IllegalStateException("Inventory row is missing.");
        if (current != draft.quantity()) {
            int delta = draft.quantity() - current;
            int updated = jdbc.update("UPDATE inventory SET quantity_on_hand=? WHERE part_id=? AND quantity_on_hand=?",
                    draft.quantity(), id, current);
            if (updated != 1) throw new IllegalStateException("Stock changed concurrently. Reload and try again.");
            jdbc.update("INSERT INTO stock_movements(part_id, movement_type, quantity, reason) VALUES (?, 'ADJUSTMENT', ?, ?)",
                    id, delta, "Inventory edit adjustment");
        }
    }

    public void deactivate(long id) {
        int changed = jdbc.update("UPDATE parts SET active=0, updated_at=CURRENT_TIMESTAMP WHERE id=? AND active=1", id);
        if (changed == 0) throw new IllegalArgumentException("Inventory item was not found or is already inactive.");
    }

    private static void bindDraft(PreparedStatement ps, PartDraft d) throws java.sql.SQLException {
        ps.setString(1, d.sku());
        ps.setString(2, d.barcode());
        ps.setString(3, d.partNumber());
        ps.setString(4, d.name());
        ps.setString(5, d.brand());
        ps.setString(6, d.company());
        ps.setString(7, d.material());
        ps.setString(8, d.color());
        if (d.categoryId() == null) ps.setNull(9, java.sql.Types.BIGINT); else ps.setLong(9, d.categoryId());
        if (d.supplierId() == null) ps.setNull(10, java.sql.Types.BIGINT); else ps.setLong(10, d.supplierId());
        ps.setLong(11, d.purchasePricePaise());
        ps.setLong(12, d.sellingPricePaise());
    }

    private Query buildQuery(InventoryFilter f) {
        StringBuilder where = new StringBuilder(" WHERE p.active=1 ");
        List<Object> args = new ArrayList<>();
        String q = normalize(f.query());
        if (!q.isEmpty()) {
            String exact = q;
            String like = "%" + q.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            where.append("""
                AND (lower(p.sku)=? OR lower(COALESCE(p.barcode,''))=? OR lower(COALESCE(p.part_number,''))=?
                  OR lower(p.sku) LIKE ? ESCAPE '!' OR lower(COALESCE(p.barcode,'')) LIKE ? ESCAPE '!'
                  OR lower(COALESCE(p.part_number,'')) LIKE ? ESCAPE '!' OR lower(p.name) LIKE ? ESCAPE '!'
                  OR lower(COALESCE(p.brand,'')) LIKE ? ESCAPE '!' OR lower(COALESCE(p.company,'')) LIKE ? ESCAPE '!'
                  OR lower(COALESCE(p.material,'')) LIKE ? ESCAPE '!' OR lower(COALESCE(p.color,'')) LIKE ? ESCAPE '!'
                  OR lower(COALESCE(c.name,'')) LIKE ? ESCAPE '!' OR lower(COALESCE(s.name,'')) LIKE ? ESCAPE '!'
                  OR EXISTS (SELECT 1 FROM part_fitments vf WHERE vf.part_id=p.id AND (
                      lower(vf.make) LIKE ? ESCAPE '!' OR lower(vf.model) LIKE ? ESCAPE '!'
                      OR lower(COALESCE(vf.engine,'')) LIKE ? ESCAPE '!' OR lower(COALESCE(vf.trim,'')) LIKE ? ESCAPE '!'
                      OR lower(COALESCE(vf.notes,'')) LIKE ? ESCAPE '!'
                      OR (?=1 AND ? BETWEEN vf.year_from AND vf.year_to))))
                """);
            args.add(exact); args.add(exact); args.add(exact);
            for (int i = 0; i < 15; i++) args.add(like);
            boolean yearQuery = q.length() == 4 && q.chars().allMatch(Character::isDigit);
            args.add(yearQuery ? 1 : 0);
            args.add(yearQuery ? Integer.parseInt(q) : 0);
        }
        appendLike(where, args, "p.brand", f.brand());
        appendLike(where, args, "p.material", f.material());
        appendLike(where, args, "p.color", f.color());
        if (f.categoryId() != null) { where.append(" AND p.category_id=?"); args.add(f.categoryId()); }
        if (f.supplierId() != null) { where.append(" AND p.supplier_id=?"); args.add(f.supplierId()); }
        if (f.minQuantity() != null) { where.append(" AND COALESCE(i.quantity_on_hand,0)>=?"); args.add(f.minQuantity()); }
        if (f.maxQuantity() != null) { where.append(" AND COALESCE(i.quantity_on_hand,0)<=?"); args.add(f.maxQuantity()); }
        if (f.minSellingPricePaise() != null) { where.append(" AND p.selling_price_paise>=?"); args.add(f.minSellingPricePaise()); }
        if (f.maxSellingPricePaise() != null) { where.append(" AND p.selling_price_paise<=?"); args.add(f.maxSellingPricePaise()); }
        switch (f.stockStatus()) {
            case IN_STOCK -> where.append(" AND COALESCE(i.quantity_on_hand,0)>0");
            case OUT_OF_STOCK -> where.append(" AND COALESCE(i.quantity_on_hand,0)=0");
            case LOW_STOCK -> where.append(" AND COALESCE(i.quantity_on_hand,0)>0 AND COALESCE(i.quantity_on_hand,0)<=COALESCE(i.reorder_level,0)");
            default -> { }
        }
        String orderColumn = switch (f.sortBy()) {
            case "sku" -> "p.sku COLLATE NOCASE";
            case "brand" -> "p.brand COLLATE NOCASE";
            case "quantity" -> "COALESCE(i.quantity_on_hand,0)";
            case "sellingPrice" -> "p.selling_price_paise";
            case "updatedAt" -> "p.updated_at";
            default -> "p.name COLLATE NOCASE";
        };
        String direction = f.ascending() ? " ASC" : " DESC";
        String rank = q.isEmpty() ? "" : "CASE WHEN lower(p.sku)=? THEN 0 WHEN lower(COALESCE(p.barcode,''))=? THEN 1 WHEN lower(COALESCE(p.part_number,''))=? THEN 2 ELSE 3 END, ";
        List<Object> selectArgs = new ArrayList<>(args);
        // ORDER BY placeholders occur after all WHERE placeholders in the SQL.
        if (!q.isEmpty()) { selectArgs.add(q); selectArgs.add(q); selectArgs.add(q); }
        String joins = " FROM parts p LEFT JOIN categories c ON c.id=p.category_id LEFT JOIN suppliers s ON s.id=p.supplier_id LEFT JOIN inventory i ON i.part_id=p.id ";
        String count = "SELECT COUNT(*)" + joins + where;
        String select = "SELECT p.id,p.sku,p.barcode,p.part_number,p.name,p.brand,p.company,p.material,p.color,COALESCE(c.name,'') category,COALESCE(s.name,'') supplier,COALESCE(i.quantity_on_hand,0) quantity,p.purchase_price_paise,p.selling_price_paise,COALESCE(i.reorder_level,0) reorder_level"
                + joins + where + " ORDER BY " + rank + orderColumn + direction + ", p.id ASC LIMIT ? OFFSET ?";
        return new Query(count, select, args, selectArgs);
    }

    private static void appendLike(StringBuilder where, List<Object> args, String column, String raw) {
        if (raw == null || raw.isBlank()) return;
        String normalized = normalize(raw);
        where.append(" AND lower(COALESCE(").append(column).append(",'')) LIKE ? ESCAPE '!'");
        args.add("%" + normalized.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String baseSelect() {
        return "SELECT p.id,p.sku,p.barcode,p.part_number,p.name,p.brand,p.company,p.material,p.color,COALESCE(c.name,'') category,COALESCE(s.name,'') supplier,COALESCE(i.quantity_on_hand,0) quantity,p.purchase_price_paise,p.selling_price_paise,COALESCE(i.reorder_level,0) reorder_level FROM parts p LEFT JOIN categories c ON c.id=p.category_id LEFT JOIN suppliers s ON s.id=p.supplier_id LEFT JOIN inventory i ON i.part_id=p.id";
    }

    private record Query(String countSql, String selectSql, List<Object> args, List<Object> selectArgs) {
        private Query {
            args = List.copyOf(args);
            selectArgs = List.copyOf(selectArgs);
        }
    }
}
