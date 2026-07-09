package com.bss.party;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
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
@SpringBootTest
@AutoConfigureObservability
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
                .andExpect(content().string(containsString("application=\"party-account\"")));
    }
}
