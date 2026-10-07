package com.bss.userroles.service;

import com.bss.userroles.security.TenantFileRefresher;
import com.bss.userroles.security.TenantRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * advanceClock moves a sandbox clone's clock forward so a tenant can watch a
 * quarter of billing happen in an afternoon. `days` arrives in the request
 * body, and the offset it produces is written into tenants.yml -- the file that
 * governs every tenant's trust settings -- and then read back by the next call.
 *
 * Two ways that used to go wrong, both from the same unguarded int add:
 * Integer.MAX_VALUE overflowed the total to a NEGATIVE offset, and a negative
 * `days` reached it directly. Either moves the clock BACKWARD, which is the one
 * thing the method's own error message promises it will not do, and a negative
 * value then stops matching the `\d+` the rewrite looks for, so the following
 * call appends a SECOND clock-offset-days key to the block.
 */
class SandboxClockBoundsTest {

    /* A sandbox clone, at the indentation the real file uses -- the block regex
     * depends on it, so the closing delimiter sits at column zero. */
    private static final String REGISTRY = """
bss:
  tenancy:
    tenants:
      - id: nova-sandbox
        issuer: http://localhost:8085/realms/nova-sandbox
        brand-name: Nova Sandbox
        sandbox: true
""";

    @TempDir
    Path dir;

    private Path registry;
    private TenantOnboardingService onboarding;

    @BeforeEach
    void setUp() throws Exception {
        registry = dir.resolve("tenants.yml");
        Files.writeString(registry, REGISTRY);

        onboarding = new TenantOnboardingService(RestClient.builder(),
                "http://localhost:8085", "admin", "admin",
                "infra/keycloak/nova-realm.json", registry.toString(),
                "http://localhost:8081", "http://localhost:8113", "http://localhost:8083",
                "http://localhost:8097", "http://localhost:8086", "http://localhost:8104",
                "http://localhost:8084", "genalpha,nova",
                mock(IdpAdminClient.class),
                mock(TenantRegistry.class), mock(TenantFileRefresher.class));
    }

    @Test
    void anOrdinaryStepMovesTheClockForward() throws Exception {
        assertThat(onboarding.advanceClock("nova-sandbox", 30).clockOffsetDays()).isEqualTo(30);
        assertThat(onboarding.advanceClock("nova-sandbox", 30).clockOffsetDays()).isEqualTo(60);

        assertThat(Files.readString(registry)).contains("clock-offset-days: \"60\"");
    }

    @Test
    void theStepThatOverflowsTheTotalIsRefused() {
        assertThatThrownBy(() -> onboarding.advanceClock("nova-sandbox", Integer.MAX_VALUE))
                .hasMessageContaining("forward");
    }

    @Test
    void aStepBackwardIsRefused() {
        assertThatThrownBy(() -> onboarding.advanceClock("nova-sandbox", -30))
                .hasMessageContaining("forward");
    }

    @Test
    void aRefusedStepLeavesTheRegistryUntouched() throws Exception {
        assertThatThrownBy(() -> onboarding.advanceClock("nova-sandbox", Integer.MIN_VALUE));

        /* The real damage was never the number -- it was the file. A negative
         * offset no longer matches the rewrite's `\d+`, so the next call takes
         * the insert branch and the block ends up with two of the same key. */
        String after = Files.readString(registry);
        assertThat(after).isEqualTo(REGISTRY);
        assertThat(after).doesNotContain("clock-offset-days");
    }

    @Test
    void theClockStopsAtACentury() throws Exception {
        for (int moved = 0; moved < 36500; moved += 3650) {
            onboarding.advanceClock("nova-sandbox", 3650);
        }

        assertThatThrownBy(() -> onboarding.advanceClock("nova-sandbox", 1))
                .hasMessageContaining("as far as a sandbox clock goes");
    }
}
