package com.bss.inventory;

import com.bss.inventory.repository.ProductRepository;
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
    private ProductRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayAppliesMigrationsAndEntitiesValidateAgainstPostgres() throws Exception {
        assertThat(postgres.isRunning()).isTrue();

        Integer applied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true", Integer.class);
        // Counted from the migration files, not hardcoded — see the sibling
        // components: a hand-edited number goes stale the moment a migration
        // lands, and this whole family skips wherever Docker is absent, so
        // nothing ever said so.
        assertThat(applied).isEqualTo(migrationFilesOnClasspath());

        Integer failed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = false", Integer.class);
        assertThat(failed).isZero();

        assertThat(repository.count()).isZero();
    }

    @Test
    void migratedSchemaHasTheProductTable() {
        Integer tables = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = 'product'
                """, Integer.class);
        assertThat(tables).isEqualTo(1);
    }

    /** Both locations apply on real Postgres: vendor-neutral plus postgres-only. */
    private int migrationFilesOnClasspath() throws Exception {
        int total = 0;
        for (String location : new String[] {"db/migration", "db/migration-postgresql"}) {
            java.net.URL dir = getClass().getClassLoader().getResource(location);
            if (dir == null) {
                continue;
            }
            java.io.File[] files = new java.io.File(dir.toURI()).listFiles(
                    (d, name) -> name.startsWith("V") && name.endsWith(".sql"));
            total += files == null ? 0 : files.length;
        }
        return total;
    }
}
