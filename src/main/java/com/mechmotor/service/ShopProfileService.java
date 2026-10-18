package com.mechmotor.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Stores shop identity and configurable tax defaults without silently changing sale totals. */
@Service
public class ShopProfileService {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern GSTIN = Pattern.compile("^[0-9A-Z]{15}$");
    private final JdbcTemplate jdbc;

    public ShopProfileService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public ShopProfile load() {
        List<ShopProfile> rows = jdbc.query("""
            SELECT shop_name,address,phone,email,gstin,state_code,tax_label,default_tax_rate_bps,tax_enabled
            FROM shop_profile WHERE id=1
            """, (rs, row) -> new ShopProfile(rs.getString("shop_name"),rs.getString("address"),
                rs.getString("phone"),rs.getString("email"),rs.getString("gstin"),rs.getString("state_code"),
                rs.getString("tax_label"),rs.getInt("default_tax_rate_bps"),rs.getBoolean("tax_enabled")));
        if (rows.isEmpty()) {
            jdbc.update("INSERT OR IGNORE INTO shop_profile(id) VALUES (1)");
            return load();
        }
        return rows.get(0);
    }

    @Transactional
    public ShopProfile save(ShopProfile draft) {
        if (draft == null) throw new IllegalArgumentException("Shop profile is required.");
        String name = clean(draft.shopName(),120,"Shop name",true);
        String address = clean(draft.address(),400,"Address",false);
        String phone = clean(draft.phone(),40,"Phone",false);
        String email = clean(draft.email(),160,"Email",false);
        String gstin = clean(draft.gstin(),15,"GSTIN",false).toUpperCase(Locale.ROOT);
        String state = clean(draft.stateCode(),2,"State code",false);
        String taxLabel = clean(draft.taxLabel(),24,"Tax label",true);
        if (!email.isEmpty() && !EMAIL.matcher(email).matches()) throw new IllegalArgumentException("Enter a valid email address.");
        if (!gstin.isEmpty() && !GSTIN.matcher(gstin).matches()) throw new IllegalArgumentException("GSTIN must contain exactly 15 letters or digits.");
        if (!state.isEmpty() && !state.matches("[0-9]{2}")) throw new IllegalArgumentException("State code must contain two digits.");
        if (draft.defaultTaxRateBps() < 0 || draft.defaultTaxRateBps() > 10000)
            throw new IllegalArgumentException("Default tax rate must be between 0% and 100%.");
        jdbc.update("""
            INSERT INTO shop_profile(id,shop_name,address,phone,email,gstin,state_code,tax_label,default_tax_rate_bps,tax_enabled,updated_at)
            VALUES (1,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
            ON CONFLICT(id) DO UPDATE SET shop_name=excluded.shop_name,address=excluded.address,
              phone=excluded.phone,email=excluded.email,gstin=excluded.gstin,state_code=excluded.state_code,
              tax_label=excluded.tax_label,default_tax_rate_bps=excluded.default_tax_rate_bps,
              tax_enabled=excluded.tax_enabled,updated_at=CURRENT_TIMESTAMP
            """,name,address,phone,email,gstin,state,taxLabel,draft.defaultTaxRateBps(),draft.taxEnabled()?1:0);
        return load();
    }

    private static String clean(String raw,int max,String label,boolean required) {
        String value=raw==null?"":raw.strip();
        if (required && value.isEmpty()) throw new IllegalArgumentException(label+" is required.");
        if (value.length()>max) throw new IllegalArgumentException(label+" must be at most "+max+" characters.");
        if (value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException(label+" contains invalid control characters.");
        return value;
    }

    public record ShopProfile(String shopName,String address,String phone,String email,String gstin,
                              String stateCode,String taxLabel,int defaultTaxRateBps,boolean taxEnabled) {
        public static ShopProfile defaults() {
            return new ShopProfile("MechMotor Vehicle Parts Shop","","","","","","GST",0,false);
        }
        public String formattedTaxRate() {
            return String.format(Locale.ROOT,"%d.%02d%%",defaultTaxRateBps/100,defaultTaxRateBps%100);
        }
    }
}
