package com.mechmotor.service;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.regex.Pattern;

/** Local user directory and non-secret report preferences. This does NOT grant login access. */
@Service
public class WorkspaceAdministrationService {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@,;]+@[^\\s@,;]+\\.[^\\s@,;]+$");
    private final JdbcTemplate jdbc;
    public WorkspaceAdministrationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct public void bootstrap() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS workspace_users (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              full_name TEXT NOT NULL,
              email TEXT NOT NULL COLLATE NOCASE UNIQUE,
              role TEXT NOT NULL CHECK(role IN ('ADMIN','STAFF','VIEWER')),
              active INTEGER NOT NULL DEFAULT 1 CHECK(active IN (0,1)),
              created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS report_preferences (
              id INTEGER PRIMARY KEY CHECK(id=1),
              default_recipient TEXT NOT NULL DEFAULT '',
              updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """);
        jdbc.update("INSERT OR IGNORE INTO report_preferences(id) VALUES (1)");
    }

    public record WorkspaceUser(long id, String name, String email, String role, boolean active) {
        @Override public String toString() {
            return name + " <" + email + "> — " + role + (active ? "" : " (inactive)");
        }
    }
    public List<WorkspaceUser> users() {
        return jdbc.query("SELECT id,full_name,email,role,active FROM workspace_users ORDER BY active DESC, full_name",
                (rs,n) -> new WorkspaceUser(rs.getLong(1),rs.getString(2),rs.getString(3),
                        rs.getString(4),rs.getBoolean(5)));
    }
    @Transactional public void save(Long id, String name, String email, String role) {
        String n = Objects.requireNonNullElse(name,"").strip();
        String e = validateEmail(email);
        if (n.isBlank() || n.length()>120 || n.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Enter a name with up to 120 characters.");
        if (!List.of("ADMIN","STAFF","VIEWER").contains(role))
            throw new IllegalArgumentException("Choose a valid directory role.");
        if (id == null) {
            jdbc.update("INSERT INTO workspace_users(full_name,email,role) VALUES(?,?,?)",n,e,role);
        } else if (jdbc.update("UPDATE workspace_users SET full_name=?,email=?,role=? WHERE id=?",
                n,e,role,id)!=1) throw new IllegalArgumentException("User no longer exists.");
    }
    @Transactional public void deactivate(long id) {
        if (jdbc.update("UPDATE workspace_users SET active=0 WHERE id=?",id)!=1)
            throw new IllegalArgumentException("User no longer exists.");
    }
    @Transactional public void reactivate(long id) {
        if (jdbc.update("UPDATE workspace_users SET active=1 WHERE id=?",id)!=1)
            throw new IllegalArgumentException("User no longer exists.");
    }
    public String defaultRecipient() {
        return jdbc.queryForObject("SELECT default_recipient FROM report_preferences WHERE id=1", String.class);
    }
    public void saveDefaultRecipient(String email) {
        jdbc.update("UPDATE report_preferences SET default_recipient=?,updated_at=CURRENT_TIMESTAMP WHERE id=1",
                email == null || email.isBlank() ? "" : validateEmail(email));
    }
    public static String validateEmail(String address) {
        String email = Objects.requireNonNullElse(address,"").strip();
        if (email.length()>254 || !EMAIL.matcher(email).matches())
            throw new IllegalArgumentException("Enter a valid single email address.");
        return email;
    }
    /** Preserve order and send each recipient only once. */
    public List<String> recipients(String given, boolean includeDefault) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (given!=null && !given.isBlank()) result.add(validateEmail(given));
        String fallback = defaultRecipient();
        if ((includeDefault || result.isEmpty()) && fallback!=null && !fallback.isBlank())
            result.add(validateEmail(fallback));
        if (result.isEmpty()) throw new IllegalArgumentException(
                "Enter a recipient email or configure a default recipient in Settings.");
        return List.copyOf(result);
    }
}
