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
    void flywayAppliesMigrationsAndEntitiesValidateAgainstPostgres() throws Exception {
        assertThat(postgres.isRunning()).isTrue();

        // Counted from the migration files themselves, not hardcoded. The
        // hardcoded number said 11 while the tree held 12, and nothing noticed
        // because this test skips wherever Docker is absent — which was
        // everywhere. A number that has to be edited by hand after every
        // migration is a number that goes stale; what is worth asserting is
        // that every migration on the classpath actually applied.
        assertThat(applied()).isEqualTo(migrationFilesOnClasspath());

        Integer failed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = false", Integer.class);
        assertThat(failed).isZero();

        assertThat(repository.count()).isZero();
    }


    private int applied() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true", Integer.class);
    }

    /** Both locations apply on real Postgres: vendor-neutral plus postgres-only. */
    private int migrationFilesOnClasspath() throws Exception {
        int total = 0;
        for (String location : new String[] {"db/migration", "db/migration-postgresql"}) {
            java.net.URL dir = getClass().getClassLoader().getResource(location);
            assertThat(dir).as(location + " is on the test classpath").isNotNull();
            java.io.File[] files = new java.io.File(dir.toURI()).listFiles(
                    (d, name) -> name.startsWith("V") && name.endsWith(".sql"));
            total += files == null ? 0 : files.length;
        }
        return total;
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
