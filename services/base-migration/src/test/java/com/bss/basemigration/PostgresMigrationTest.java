package com.bss.basemigration;

import com.bss.basemigration.repository.MigrationPlanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PostgresMigrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MigrationPlanRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayAppliesMigrationsAndEntitiesValidateAgainstPostgres() throws Exception {
        assertThat(postgres.isRunning()).isTrue();
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true", Integer.class);
        // Counted from the migration files, not hardcoded. Twelve of these tests
        // asserted a number the tree had already outgrown, and nothing noticed,
        // because the whole family skips wherever Docker is absent — which was
        // everywhere. A number edited by hand after every migration goes stale;
        // what is worth asserting is that every migration on the classpath
        // applied, and that none failed.
        assertThat(applied).isEqualTo(migrationFilesOnClasspath());

        Integer failed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = false", Integer.class);
        assertThat(failed).isZero();
        assertThat(repository.count()).isZero();
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
