package com.ticketmaster.user;

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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Same load-bearing shape as auth-service's AuthApplicationTest: ddl-auto
 * is `validate`, so the context only starts if Flyway actually produced
 * the schema the entities expect.
 */
@SpringBootTest
class UserServiceApplicationTest {

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
                     "WHERE table_schema = 'user_service' ORDER BY table_name")) {

            StringBuilder found = new StringBuilder();
            while (rs.next()) {
                found.append(rs.getString(1)).append(' ');
            }

            String tables = found.toString();
            assertTrue(tables.contains("user_profiles"), "user_profiles table missing: " + tables);
            assertTrue(tables.contains("user_preferences"), "user_preferences table missing: " + tables);
            assertTrue(tables.contains("saved_payment_methods"), "saved_payment_methods table missing: " + tables);
        }
    }
}
