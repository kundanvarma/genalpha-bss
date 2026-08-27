package com.bss.som;

import com.bss.som.entity.ServiceInstance;
import com.bss.som.repository.ServiceInstanceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The RESTRICT primitive: a barring profile on a running line — lighter than
 * suspend, emergency whitelist never optional, back-office only. Suspend with
 * reason=nonpayment persists and exposes the reason.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RestrictionApiTest {

    private static final String BASE = "/tmf-api/serviceInventory/v4";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ServiceInstanceRepository services;

    private static RequestPostProcessor machine() {
        return jwt().authorities(
                new SimpleGrantedAuthority("service:read"),
                new SimpleGrantedAuthority("service:write"));
    }

    private static RequestPostProcessor customer(String sub) {
        return jwt().jwt(j -> j.subject(sub)).authorities(
                new SimpleGrantedAuthority("customer"));
    }

    private String mintService(String owner) {
        ServiceInstance s = new ServiceInstance();
        s.setId(UUID.randomUUID().toString());
        s.setHref(BASE + "/service/" + s.getId());
        s.setTenantId("genalpha");
        s.setName("Mobile 10 GB");
        s.setState(ServiceInstance.ACTIVE);
        s.setServiceOrderId("so-" + s.getId().substring(0, 8));
        s.setOwnerPartyId(owner);
        s.setCreatedAt(OffsetDateTime.now());
        s.setLastUpdate(OffsetDateTime.now());
        services.save(s);
        return s.getId();
    }

    @Test
    void restrict_appliesBarringProfile_emergencyWhitelistIsNotAKnob() throws Exception {
        String id = mintService("cust-restrict");

        // the caller tries to switch the whitelist OFF — statute wins
        mockMvc.perform(post(BASE + "/service/" + id + "/restrict").with(machine())
                        .contentType("application/json")
                        .content("""
                                {"reason":"nonpayment","profile":{"outgoingBarred":true,
                                 "dataThrottled":true,"emergencyWhitelist":false}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restrictionProfile.emergencyWhitelist").value(true))
                .andExpect(jsonPath("$.state").value("active"));

        mockMvc.perform(get(BASE + "/service/" + id).with(machine()))
                .andExpect(jsonPath("$.restriction.reason").value("nonpayment"))
                .andExpect(jsonPath("$.restriction.profile.outgoingBarred").value(true))
                .andExpect(jsonPath("$.restriction.profile.emergencyWhitelist").value(true))
                .andExpect(jsonPath("$.state").value("active"));

        // a customer cannot bar a line, not even their own
        mockMvc.perform(post(BASE + "/service/" + id + "/restrict").with(customer("cust-restrict"))
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(BASE + "/service/" + id + "/unrestrict").with(machine()))
                .andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/service/" + id).with(machine()))
                .andExpect(jsonPath("$.restriction").doesNotExist());
        // lifting twice is an honest 400
        mockMvc.perform(post(BASE + "/service/" + id + "/unrestrict").with(machine()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void suspendForNonpayment_persistsAndExposesTheReason() throws Exception {
        String id = mintService("cust-suspend");

        mockMvc.perform(post(BASE + "/service/" + id + "/suspend").with(machine())
                        .contentType("application/json").content("{\"reason\":\"nonpayment\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("nonpayment"));

        mockMvc.perform(get(BASE + "/service/" + id).with(machine()))
                .andExpect(jsonPath("$.state").value("suspended"))
                .andExpect(jsonPath("$.suspendReason").value("nonpayment"));

        mockMvc.perform(post(BASE + "/service/" + id + "/resume").with(machine()))
                .andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/service/" + id).with(machine()))
                .andExpect(jsonPath("$.state").value("active"))
                .andExpect(jsonPath("$.suspendReason").doesNotExist());
    }
}
