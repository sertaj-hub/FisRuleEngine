package com.fisre.engine.web;

import com.fisre.engine.config.FisreProperties;
import java.util.Arrays;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Access rules of the rule UI (ADR-0010, REQ-RUI-005/012). Only active when the app runs as a web server (FISRE_JOB=serve).
 * HTTP Basic against aml.rule_user; stateless, so there are no cookies and no CSRF surface. Serve it behind TLS.
 */
@Configuration
@EnableWebSecurity
@ConditionalOnWebApplication
public class SecurityConfig {

    @Bean
    SecurityFilterChain chain(HttpSecurity http) throws Exception {
        String[] readers = {"VIEWER", "AUTHOR", "APPROVER"};
        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // no WWW-Authenticate header: the page does its own login form instead of the browser's pop-up
                .httpBasic(b -> b.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .headers(h -> h.contentSecurityPolicy(p -> p.policyDirectives("default-src 'self'; style-src 'self'; script-src 'self'; frame-ancestors 'none'"))
                        .cacheControl(Customizer.withDefaults()))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/app.js", "/app.css", "/favicon.ico").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/**").hasAnyRole(readers)
                        .requestMatchers(HttpMethod.POST, "/api/dry-run", "/api/rules/*/versions", "/api/rules/*/versions/*/submit", "/api/rules/*/versions/*/dry-run").hasRole("AUTHOR")
                        .requestMatchers(HttpMethod.PUT, "/api/rules/*/versions/*").hasRole("AUTHOR")
                        .requestMatchers(HttpMethod.POST, "/api/rules/*/versions/*/approve", "/api/rules/*/versions/*/reject", "/api/rules/*/versions/*/retire").hasRole("APPROVER")
                        .anyRequest().denyAll());
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService users(JdbcTemplate jdbc, FisreProperties props) {
        String sql = "SELECT username, password_hash, roles FROM " + props.schemas().aml() + ".rule_user WHERE username = ? AND enabled";
        return username -> jdbc.query(sql, rs -> {
            if (!rs.next()) {
                throw new UsernameNotFoundException("unknown user");
            }
            return User.withUsername(rs.getString("username")).password(rs.getString("password_hash"))
                    .roles(Arrays.stream(rs.getString("roles").split(",")).map(String::trim).toArray(String[]::new)).build();
        }, username);
    }
}
