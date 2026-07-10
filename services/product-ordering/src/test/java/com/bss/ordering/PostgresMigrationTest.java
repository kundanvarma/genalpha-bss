package com.bss.ordering;

import com.bss.ordering.repository.ProductOrderRepository;
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
 * but not identical — notably for {@code TIMESTAMP WITH TIME ZONE}, which this
 * service's order_date column relies on.
 *
 * <p>Deliberately does not activate the "test" profile: that profile points the
 * datasource at H2. This test loads the production configuration — Flyway enabled,
 * {@code ddl-auto: validate} — so simply starting the context proves the migrations
 * apply cleanly and the JPA entities match the resulting schema.
 *
 * <p>Skipped when Docker is unavailable. CI has Docker, and is where this runs.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PostgresMigrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ProductOrderRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayAppliesMigrationsAndEntitiesValidateAgainstPostgres() {
        assertThat(postgres.isRunning()).isTrue();

        Integer applied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true", Integer.class);
        assertThat(applied).isEqualTo(6);

        assertThat(repository.count()).isZero();
    }

    @Test
    void orderDateIsATimestampWithTimeZone() {
        String dataType = jdbcTemplate.queryForObject("""
                SELECT data_type FROM information_schema.columns
                WHERE table_name = 'product_order' AND column_name = 'order_date'
                """, String.class);
        assertThat(dataType).isEqualTo("timestamp with time zone");
    }
}
