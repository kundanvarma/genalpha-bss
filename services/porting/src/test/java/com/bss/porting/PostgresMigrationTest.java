package com.bss.porting;

import com.bss.porting.repository.PortingOrderRepository;
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
    private PortingOrderRepository repository;

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
