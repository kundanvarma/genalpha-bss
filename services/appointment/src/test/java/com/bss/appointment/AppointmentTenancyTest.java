package com.bss.appointment;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pool-model tenancy: rows belong to the tenant of the verified token issuer.
 * Cross-tenant access must behave as if the resource does not exist (404,
 * never 403), lists must never leak foreign rows, and party scoping stays an
 * inner predicate — even the same subject under another tenant sees nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AppointmentTenancyTest {

    private static final String BASE = "/tmf-api/appointment/v4";
    private static final String ISSUER_A = "https://idp.tenant-a.test/realms/bss";
    private static final String ISSUER_B = "https://idp.tenant-b.test/realms/bss";

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor customerOf(String issuer, String sub) {
        return jwt().jwt(j -> j.issuer(issuer).subject(sub)).authorities(
                new SimpleGrantedAuthority("customer"),
                new SimpleGrantedAuthority("appointment:read"),
                new SimpleGrantedAuthority("appointment:write"));
    }

    private static RequestPostProcessor staffOf(String issuer) {
        return jwt().jwt(j -> j.issuer(issuer)).authorities(
                new SimpleGrantedAuthority("appointment:read"),
                new SimpleGrantedAuthority("appointment:write"));
    }

    private String book(RequestPostProcessor token, OffsetDateTime start) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE + "/appointment").with(token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"validFor\": {\"startDateTime\": \"%s\", \"endDateTime\": \"%s\"}}"
                                .formatted(start, start.plusHours(2))))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private static OffsetDateTime futureSlot(int daysAhead, int hour) {
        return LocalDate.now().plusDays(daysAhead).atTime(LocalTime.of(hour, 0)).atOffset(ZoneOffset.UTC);
    }

    @Test
    void tenantsCannotSeeEachOthersAppointments() throws Exception {
        String idA = book(customerOf(ISSUER_A, "cust-tenancy-1"), futureSlot(20, 9));

        // The owner reads it back; the other tenant's staff gets 404, not 403.
        mockMvc.perform(get(BASE + "/appointment/" + idA).with(customerOf(ISSUER_A, "cust-tenancy-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("confirmed"));
        mockMvc.perform(get(BASE + "/appointment/" + idA).with(staffOf(ISSUER_B)))
                .andExpect(status().isNotFound());

        // The SAME subject under another tenant's issuer sees nothing either:
        // tenant is an outer predicate on top of party scoping.
        mockMvc.perform(get(BASE + "/appointment/" + idA).with(customerOf(ISSUER_B, "cust-tenancy-1")))
                .andExpect(status().isNotFound());

        // Tenant-B lists (staff, unscoped by party) never leak the foreign row.
        MvcResult listB = mockMvc.perform(get(BASE + "/appointment?limit=100").with(staffOf(ISSUER_B)))
                .andExpect(status().isOk()).andReturn();
        if (listB.getResponse().getContentAsString().contains(idA)) {
            throw new AssertionError("tenant B list leaked tenant A's appointment");
        }
    }

    @Test
    void writesAreConfinedToTheCallersTenant() throws Exception {
        String idA = book(customerOf(ISSUER_A, "cust-tenancy-2"), futureSlot(21, 11));

        mockMvc.perform(patch(BASE + "/appointment/" + idA).with(staffOf(ISSUER_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"cancelled\"}"))
                .andExpect(status().isNotFound());

        // Still intact and untouched for its owner.
        mockMvc.perform(get(BASE + "/appointment/" + idA).with(customerOf(ISSUER_A, "cust-tenancy-2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("confirmed"));
    }

    @Test
    void slotCapacityIsPerTenant() throws Exception {
        OffsetDateTime start = futureSlot(22, 13);
        book(customerOf(ISSUER_A, "cust-cap-1"), start);
        book(customerOf(ISSUER_A, "cust-cap-2"), start);
        book(customerOf(ISSUER_A, "cust-cap-3"), start); // tenant-a slot now full

        mockMvc.perform(post(BASE + "/appointment").with(customerOf(ISSUER_A, "cust-cap-4"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"validFor\": {\"startDateTime\": \"%s\", \"endDateTime\": \"%s\"}}"
                                .formatted(start, start.plusHours(2))))
                .andExpect(status().isConflict());

        // Tenant B's installers are a different fleet: the same slot is free.
        book(customerOf(ISSUER_B, "cust-cap-5"), start);
    }
}
