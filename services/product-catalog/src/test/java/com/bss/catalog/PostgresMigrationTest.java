package com.bss.catalog;

import com.bss.catalog.repository.ProductOfferingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the Flyway migrations against a real PostgreSQL, the engine the services
 * actually deploy against. The other tests use H2 in PostgreSQL mode, which is close
 * but not identical.
 *
 * <p>Deliberately does not activate the "test" profile: that profile points the
 * datasource at H2. This test loads the production configuration — Flyway enabled,
 * {@code ddl-auto: validate} — so simply starting the context proves the migrations
 * apply cleanly and the JPA entities match the resulting schema. A drifted column
 * would fail context startup with a SchemaManagementException.
 *
 * <p>Skipped when Docker is unavailable, so the suite still runs on machines without
 * it. CI has Docker, and is where this test is expected to execute.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PostgresMigrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ProductOfferingRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayAppliesMigrationsAndEntitiesValidateAgainstPostgres() {
        assertThat(postgres.isRunning()).isTrue();

        Integer applied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true", Integer.class);
        assertThat(applied).isEqualTo(5);

        // A real query against the migrated schema, on the real engine.
        assertThat(repository.count()).isZero();
    }

    @Test
    void migratedSchemaHasTheExpectedTables() {
        Integer tables = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('product_offering', 'category', 'product_specification')
                """, Integer.class);
        assertThat(tables).isEqualTo(3);
    }
}
