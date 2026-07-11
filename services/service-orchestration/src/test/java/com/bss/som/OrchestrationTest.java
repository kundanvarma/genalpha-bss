package com.bss.som;

import com.bss.som.client.OrderingClient;
import com.bss.som.security.TenantContext;
import com.bss.som.service.OrchestrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrchestrationTest {

    @Autowired
    private OrchestrationService orchestration;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrderingClient ordering;

    private Map<String, Object> order(String id, String state) {
        return Map.of(
                "id", id, "state", state,
                "relatedParty", List.of(Map.of("id", "cust-som", "role", "customer")),
                "productOrderItem", List.of(
                        Map.of("id", "1", "productOffering", Map.of("id", "po-a", "name", "Fiber 1000")),
                        Map.of("id", "2", "productOffering", Map.of("id", "po-b", "name", "TV Max"))));
    }

    @Test
    void decomposesActivatesAndCallsBack_idempotently() throws Exception {
        try (TenantContext ignored = TenantContext.actAs("genalpha")) {
            orchestration.orchestrate(order("po-1", "acknowledged"));
            orchestration.orchestrate(order("po-1", "acknowledged")); // duplicate delivery
            orchestration.orchestrate(order("po-2", "cancelled"));    // not for us
        }
        verify(ordering, times(1)).complete("po-1");
        verify(ordering, never()).complete("po-2");

        mockMvc.perform(get("/tmf-api/serviceOrdering/v4/serviceOrder")
                        .param("productOrderId", "po-1")
                        .with(jwt().authorities(new SimpleGrantedAuthority("service:read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].state").value("completed"));

        mockMvc.perform(get("/tmf-api/serviceInventory/v4/service")
                        .param("relatedPartyId", "cust-som")
                        .with(jwt().authorities(new SimpleGrantedAuthority("service:read"))))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].state").value("active"));

        // reads require the authority
        mockMvc.perform(get("/tmf-api/serviceOrdering/v4/serviceOrder"))
                .andExpect(status().isUnauthorized());
    }
}
