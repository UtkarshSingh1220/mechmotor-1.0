package com.mechmotor.repository;

import com.mechmotor.domain.Part;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Locale;

@Repository
public class PartRepository {
    private final JdbcTemplate jdbc;
    public PartRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Part> search(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.strip().replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
        String pattern = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        String sql = """
            SELECT p.id, p.sku, p.barcode, p.part_number, p.name, p.brand, p.company,
                   p.material, p.color, COALESCE(c.name, '') category,
                   COALESCE(s.name, '') supplier, COALESCE(i.quantity_on_hand, 0) quantity,
                   p.selling_price_paise
            FROM parts p
            LEFT JOIN categories c ON c.id = p.category_id
            LEFT JOIN suppliers s ON s.id = p.supplier_id
            LEFT JOIN inventory i ON i.part_id = p.id
            WHERE p.active = 1 AND (
                ? = '' OR lower(p.sku) = ? OR lower(COALESCE(p.barcode,'')) = ?
                OR lower(COALESCE(p.part_number,'')) LIKE ? ESCAPE '\\'
                OR lower(p.name) LIKE ? ESCAPE '\\'
                OR lower(COALESCE(p.brand,'')) LIKE ? ESCAPE '\\'
                OR lower(COALESCE(p.company,'')) LIKE ? ESCAPE '\\'
                OR lower(COALESCE(p.material,'')) LIKE ? ESCAPE '\\'
                OR lower(COALESCE(p.color,'')) LIKE ? ESCAPE '\\'
                OR lower(COALESCE(c.name,'')) LIKE ? ESCAPE '\\'
                OR lower(COALESCE(s.name,'')) LIKE ? ESCAPE '\\'
            )
            ORDER BY CASE WHEN lower(p.sku) = ? THEN 0
                          WHEN lower(COALESCE(p.barcode,'')) = ? THEN 1
                          WHEN lower(COALESCE(p.part_number,'')) = ? THEN 2 ELSE 3 END,
                     p.name COLLATE NOCASE
            LIMIT 500
            """;
        return jdbc.query(sql, (rs, row) -> new Part(
                rs.getLong("id"), rs.getString("sku"), rs.getString("barcode"),
                rs.getString("part_number"), rs.getString("name"), rs.getString("brand"),
                rs.getString("company"), rs.getString("material"), rs.getString("color"),
                rs.getString("category"), rs.getString("supplier"), rs.getInt("quantity"),
                rs.getLong("selling_price_paise")),
                query, query, query, pattern, pattern, pattern, pattern, pattern, pattern,
                pattern, pattern, query, query, query);
    }
}
