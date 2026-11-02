package com.bss.intelligence.chat;

import com.bss.intelligence.client.BssApiClient;
import com.bss.intelligence.security.TenantContext;
import com.bss.intelligence.security.TenantScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * A guest's chat answers only from their own operator's shop (#422): the shelf
 * a guest is answered from is cached per tenant, so one operator's visitor is
 * never answered from another operator's products.
 */
@SpringBootTest
@ActiveProfiles("test")
class GuestShelfTenancyTest {

    @Autowired
    private CareChatService chat;

    @Autowired
    private TenantScope tenantScope;

    @MockitoBean
    private BssApiClient bss;

    @Test
    void eachTenantsGuestSeesOnlyItsOwnShelf_evenWithinTheCacheWindow() {
        given(bss.offeringsFirstPage()).willAnswer(i -> List.of(
                Map.of("name", "Plan of " + tenantScope.currentTenantId(), "lifecycleStatus", "Active")));
        String genalpha;
        String taranga;
        try (TenantContext ignored = TenantContext.actAs("genalpha")) {
            genalpha = chat.guestContext();
        }
        try (TenantContext ignored = TenantContext.actAs("taranga")) {
            taranga = chat.guestContext();
        }
        assertThat(genalpha).contains("Plan of genalpha").doesNotContain("taranga");
        assertThat(taranga).contains("Plan of taranga").doesNotContain("genalpha");
    }
}
