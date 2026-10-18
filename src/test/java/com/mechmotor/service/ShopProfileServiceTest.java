package com.mechmotor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.*;

class ShopProfileServiceTest {
    @TempDir Path tempDir;
    private ShopProfileService service;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource source = new SQLiteDataSource(config);
        source.setUrl("jdbc:sqlite:" + tempDir.resolve("shop-profile.db").toAbsolutePath());
        try (Connection connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new org.springframework.core.io.ClassPathResource("schema.sql"));
        }
        service = new ShopProfileService(new JdbcTemplate(source));
    }

    @Test
    void defaultsLoadAndSavedProfilePersistsAcrossReads() {
        var defaults = service.load();
        assertEquals("MechMotor Vehicle Parts Shop", defaults.shopName());
        var saved = service.save(new ShopProfileService.ShopProfile("North Road Spares",
                "12 Market Road, Gurugram", "+91 9876543210", "sales@example.com",
                "22AAAAA0000A1Z5", "06", "GST", 1800, true));
        assertEquals("North Road Spares", saved.shopName());
        assertEquals("18.00%", saved.formattedTaxRate());
        assertEquals("22AAAAA0000A1Z5", service.load().gstin());
        assertTrue(service.load().taxEnabled());
    }

    @Test
    void rejectsInvalidEmailGstinStateCodeAndTaxRate() {
        assertThrows(IllegalArgumentException.class, () -> service.save(new ShopProfileService.ShopProfile(
                "Shop", "", "", "not-an-email", "", "", "GST", 0, false)));
        assertThrows(IllegalArgumentException.class, () -> service.save(new ShopProfileService.ShopProfile(
                "Shop", "", "", "", "bad-gstin", "", "GST", 0, false)));
        assertThrows(IllegalArgumentException.class, () -> service.save(new ShopProfileService.ShopProfile(
                "Shop", "", "", "", "", "ABC", "GST", 0, false)));
        assertThrows(IllegalArgumentException.class, () -> service.save(new ShopProfileService.ShopProfile(
                "Shop", "", "", "", "", "", "GST", 10001, true)));
    }
}
