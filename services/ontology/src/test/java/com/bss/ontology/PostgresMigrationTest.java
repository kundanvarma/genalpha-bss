package com.bss.ontology;

import com.bss.ontology.receipt.ReceiptStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The receipt store on a real PostgreSQL, the engine the service deploys against:
 * every migration applies, and — the reason this module has a Postgres test at all —
 * a machine's money budget per target holds under real concurrency (review finding 6).
 * Reading "what has been spent" and writing the new attempt were two steps with no
 * lock, so N parallel credits of 25 on one bill all read 0 and all went through.
 */
@SpringBootTest(properties = {
        // no Kafka here; the store under test is the database, not the bus
        "ontology.receipts-required=false",
        "spring.kafka.bootstrap-servers=localhost:1"})
@Testcontainers(disabledWithoutDocker = true)
class PostgresMigrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReceiptStore store;

    @Test
    void flywayAppliesMigrationsAndEntitiesValidateAgainstPostgres() throws Exception {
        assertThat(postgres.isRunning()).isTrue();
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true", Integer.class);
        assertThat(applied).isEqualTo(migrationFilesOnClasspath());
        Integer failed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = false", Integer.class);
        assertThat(failed).isZero();
    }

    @Test
    void parallelMachineCreditsOnOneBillCannotAllSpendTheSameBudget() throws Exception {
        // eight credits of 25 at once, on one bill, against a budget of 25: exactly one goes
        assertThat(racedAttempts(8, "25")).as("one 25 fits a budget of 25; the other seven go to a person").isEqualTo(1);
        // eight of 10 at once: two fit (20), the third would make 30
        assertThat(racedAttempts(8, "10")).as("10 + 10 fit a budget of 25; 30 does not").isEqualTo(2);
    }

    /** Release {@code callers} credit attempts on one fresh bill at the same instant; count those written. */
    private int racedAttempts(int callers, String amount) throws Exception {
        String bill = "bill-race-" + UUID.randomUUID();
        ReceiptStore.Budget budget = new ReceiptStore.Budget("billId", bill, "amount", new BigDecimal(amount),
                new BigDecimal("25"), OffsetDateTime.now().minusDays(30));
        String inputs = "{\"billId\":\"" + bill + "\",\"amount\":\"" + amount + "\",\"reason\":\"goodwill\"}";
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<ReceiptStore.BudgetedAttempt>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return store.recordAttemptWithinBudget(budget, "genalpha", "issueCredit", "bss-worker",
                            null, null, "agent,workforce:use", "1", inputs, "{}", "billing / billing.creditNote",
                            UUID.randomUUID().toString());
                }));
            }
            go.countDown();
            int written = 0;
            for (Future<ReceiptStore.BudgetedAttempt> f : results) {
                if (f.get(60, TimeUnit.SECONDS).attempt() != null) {
                    written++;
                }
            }
            Integer rows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM action_receipt WHERE phase = 'attempt' AND inputs LIKE ?",
                    Integer.class, "%" + bill + "%");
            assertThat(rows).as("the store holds exactly the attempts it said it wrote").isEqualTo(written);
            return written;
        } finally {
            pool.shutdownNow();
        }
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
