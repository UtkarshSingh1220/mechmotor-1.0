package com.mechmotor.config;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class DatabaseConfiguration {
    @Bean
    DataSource dataSource() throws Exception {
        String configured = System.getProperty("mechmotor.dataDir");
        String localAppData = System.getenv("LOCALAPPDATA");
        Path dataDir;
        if (configured != null && !configured.isBlank()) {
            dataDir = Path.of(configured);
        } else {
            Path baseDir = localAppData == null || localAppData.isBlank()
                    ? Path.of(System.getProperty("user.home"))
                    : Path.of(localAppData);
            dataDir = baseDir.resolve("MechMotor").resolve("data");
        }

        Files.createDirectories(dataDir);

        // SQLite foreign-key enforcement is connection-specific. Configure the
        // datasource so every connection enforces the schema's REFERENCES clauses.
        SQLiteConfig sqliteConfig = new SQLiteConfig();
        sqliteConfig.enforceForeignKeys(true);
        SQLiteDataSource source = new SQLiteDataSource(sqliteConfig);
        source.setUrl("jdbc:sqlite:" + dataDir.resolve("mechmotor.db").toAbsolutePath());
        return source;
    }
}
