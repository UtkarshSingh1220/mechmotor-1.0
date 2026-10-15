package com.mechmotor.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseConfigurationTest {
    @TempDir
    Path tempDir;

    @Test
    void enforcesForeignKeysOnEveryDatabaseConnection() throws Exception {
        String previousDataDir = System.getProperty("mechmotor.dataDir");
        System.setProperty("mechmotor.dataDir", tempDir.toString());
        try {
            DataSource dataSource = new DatabaseConfiguration().dataSource();

            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                try (ResultSet result = statement.executeQuery("PRAGMA foreign_keys")) {
                    result.next();
                    assertEquals(1, result.getInt(1));
                }

                statement.execute("CREATE TABLE parent (id INTEGER PRIMARY KEY)");
                statement.execute("CREATE TABLE child (parent_id INTEGER REFERENCES parent(id))");
                assertThrows(SQLException.class,
                        () -> statement.executeUpdate("INSERT INTO child(parent_id) VALUES (99)"));
            }

            // A fresh connection must receive the same safety setting.
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("PRAGMA foreign_keys")) {
                result.next();
                assertEquals(1, result.getInt(1));
            }
        } finally {
            if (previousDataDir == null) {
                System.clearProperty("mechmotor.dataDir");
            } else {
                System.setProperty("mechmotor.dataDir", previousDataDir);
            }
        }
    }
}
