package com.ticketmaster.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Starts the real application against a real Postgres (ADR-008's
 * integration tier: no mocked database).
 *
 * Load-bearing despite looking trivial. Because spring.jpa.hibernate
 * .ddl-auto is `validate`, the context only starts if Flyway's migration
 * actually produced the schema the entities expect. Migration drift fails
 * here rather than in production.
 */
@SpringBootTest
class AuthApplicationTest {

    // Started in a static block rather than via @Container so the
    // container is up before @DynamicPropertySource is evaluated, and is
    // shared by every test in the class instead of restarting per test.
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    DataSource dataSource;

    @Test
    void contextLoads() {
        // Reaching this point means Spring started and Flyway ran clean.
    }

    @Test
    void flywayCreatedTheBaselineSchema() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT table_name FROM information_schema.tables " +
                     "WHERE table_schema = 'public' ORDER BY table_name")) {

            StringBuilder found = new StringBuilder();
            while (rs.next()) {
                found.append(rs.getString(1)).append(' ');
            }

            String tables = found.toString();
            assertTrue(tables.contains("users"), "users table missing: " + tables);
            assertTrue(tables.contains("user_roles"), "user_roles table missing: " + tables);
            assertTrue(tables.contains("refresh_tokens"), "refresh_tokens table missing: " + tables);
        }
    }

    @Test
    void emailComparisonIsCaseInsensitive() throws Exception {
        // CITEXT is a correctness guarantee, not a formatting nicety: it
        // makes a case-duplicate account unrepresentable.
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("INSERT INTO users (email, password_hash, created_at, updated_at) " +
                    "VALUES ('Case@Example.com', 'x', now(), now())");

            try (ResultSet rs = stmt.executeQuery(
                    "SELECT count(*) FROM users WHERE email = 'case@example.com'")) {
                rs.next();
                assertEquals(1, rs.getInt(1));
            }
        }
    }
}
