package com.mechmotor.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Local contact/user directory. This is NOT an authentication or RBAC provider. */
@Service
public class WorkspaceUserService {
    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$");
    private final JdbcTemplate jdbc;
    public WorkspaceUserService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record User(long id, String name, String email, String role, boolean active) {}
    public List<User> list() {
        return jdbc.query("SELECT id,display_name,email,role,active FROM app_users ORDER BY active DESC,display_name,id",
                (rs,n) -> new User(rs.getLong(1),rs.getString(2),rs.getString(3),
                        rs.getString(4),rs.getInt(5)==1));
    }
    @Transactional
    public void create(String name, String email, String role) {
        validate(name,email,role);
        jdbc.update("INSERT INTO app_users(display_name,email,role) VALUES (?,?,?)",
                name.trim(),email.trim().toLowerCase(Locale.ROOT),role);
    }
    @Transactional
    public void update(long id, String name, String email, String role) {
        validate(name,email,role);
        if (jdbc.update("UPDATE app_users SET display_name=?,email=?,role=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND active=1",
                name.trim(),email.trim().toLowerCase(Locale.ROOT),role,id)!=1)
            throw new IllegalArgumentException("Active user not found.");
    }
    @Transactional
    public void deactivate(long id) {
        if (jdbc.update("UPDATE app_users SET active=0,updated_at=CURRENT_TIMESTAMP WHERE id=? AND active=1",id)!=1)
            throw new IllegalArgumentException("Active user not found.");
    }
    public String defaultRecipient() {
        return jdbc.query("SELECT value FROM app_preferences WHERE key='default_report_email'",
                (rs,n)->rs.getString(1)).stream().findFirst().orElse("");
    }
    public void setDefaultRecipient(String email) {
        requireEmail(email);
        jdbc.update("INSERT INTO app_preferences(key,value) VALUES ('default_report_email',?) "
                +"ON CONFLICT(key) DO UPDATE SET value=excluded.value",email.trim().toLowerCase(Locale.ROOT));
    }
    public static void requireEmail(String email) {
        if(email==null || !EMAIL.matcher(email.trim()).matches() || email.length()>254)
            throw new IllegalArgumentException("Enter a valid email address.");
    }
    private static void validate(String name,String email,String role) {
        if(name==null || name.isBlank() || name.trim().length()>120)
            throw new IllegalArgumentException("Enter a user name (up to 120 characters).");
        requireEmail(email);
        if(!List.of("ADMIN","MANAGER","STAFF").contains(role))
            throw new IllegalArgumentException("Choose a valid user role.");
    }
}
