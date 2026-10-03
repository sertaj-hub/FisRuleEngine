package com.fisre.engine.web;

import com.fisre.engine.config.FisreProperties;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** Users of the rule UI (aml.rule_user). Passwords are stored only as bcrypt hashes (REQ-RUI-012). */
@Service
public class RuleUserService {

    public static final Set<String> ROLES = Set.of("VIEWER", "AUTHOR", "APPROVER");
    private static final int MIN_PASSWORD = 12;

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();   // {bcrypt}
    private final String table;

    public RuleUserService(JdbcTemplate jdbc, FisreProperties props) {
        this.jdbc = jdbc;
        this.table = props.schemas().aml() + ".rule_user";
    }

    /** Creates the user, or replaces the password and roles of an existing one. */
    public void createOrUpdate(String username, String password, String roles) {
        if (username == null || !username.matches("[A-Za-z0-9._@-]{2,100}")) {
            throw new IllegalArgumentException("username must be 2 to 100 characters: letters, digits and . _ @ -");
        }
        if (password == null || password.length() < MIN_PASSWORD) {
            throw new IllegalArgumentException("password must be at least " + MIN_PASSWORD + " characters");
        }
        Set<String> r = Arrays.stream((roles == null ? "" : roles).split(",")).map(String::trim).filter(x -> !x.isEmpty()).collect(Collectors.toCollection(java.util.TreeSet::new));
        if (r.isEmpty() || !ROLES.containsAll(r)) {
            throw new IllegalArgumentException("roles must be a comma-separated list of " + new java.util.TreeSet<>(ROLES));
        }
        jdbc.update("INSERT INTO " + table + " (username, password_hash, roles) VALUES (?, ?, ?) ON CONFLICT (username) DO UPDATE SET password_hash = EXCLUDED.password_hash,"
                + " roles = EXCLUDED.roles, enabled = TRUE", username, encoder.encode(password), String.join(",", r));
    }
}
