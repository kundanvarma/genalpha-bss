package com.bss.appointment;

import com.bss.appointment.repository.AppointmentRepository;
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
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PostgresMigrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private AppointmentRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayAppliesMigrationsAndEntitiesValidateAgainstPostgres() {
        assertThat(postgres.isRunning()).isTrue();

        Integer applied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true", Integer.class);
        assertThat(applied).isEqualTo(3);

        assertThat(repository.count()).isZero();
    }

    @Test
    void migratedSchemaHasTheExpectedTables() {
        Integer tables = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('appointment', 'event_outbox')
                """, Integer.class);
        assertThat(tables).isEqualTo(2);
    }
}
