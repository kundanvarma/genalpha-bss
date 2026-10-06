package com.bss.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Prometheus scrape endpoint must be reachable without a token (Prometheus
 * does not authenticate) and expose JVM metrics tagged with the application name.
 */
// Boot 4 removes this annotation — not moved, gone. Without it a
// @SpringBootTest disables metrics export, so /actuator/prometheus answers with
// nothing and this test would pass while proving the opposite of what it claims.
//
// `spring.test.observability.auto-configure` is the same switch as a property,
// and it exists in BOTH 3.5.16 and 4.0.8 — so this lands today, on the version
// we run, and survives the migration (#199) instead of blocking it.
@SpringBootTest(properties = "spring.test.observability.auto-configure=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ObservabilityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void prometheusEndpoint_isOpenAndServesMetrics() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("jvm_memory_used_bytes")))
                .andExpect(content().string(containsString("application=\"product-catalog\"")));
    }
}
