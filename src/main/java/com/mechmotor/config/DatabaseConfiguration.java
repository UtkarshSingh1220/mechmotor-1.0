package com.mechmotor.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.context.annotation.Bean;
import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class DatabaseConfiguration {
    @Bean
    DataSource dataSource() throws Exception {
        String configured = System.getProperty("mechmotor.dataDir");
        Path dataDir = configured == null || configured.isBlank()
                ? Path.of(System.getenv().getOrDefault("LOCALAPPDATA", System.getProperty("user.home")),
                    "MechMotor", "data")
                : Path.of(configured);
        Files.createDirectories(dataDir);
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.sqlite.JDBC");
        source.setUrl("jdbc:sqlite:" + dataDir.resolve("mechmotor.db").toAbsolutePath());
        return source;
    }
}
