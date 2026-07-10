package com.bss.appointment;

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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AppointmentApiTest {

    private static final String BASE = "/tmf-api/appointment/v4";

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor customer(String sub) {
        return jwt().jwt(j -> j.subject(sub)).authorities(
                new SimpleGrantedAuthority("customer"),
                new SimpleGrantedAuthority("appointment:read"),
                new SimpleGrantedAuthority("appointment:write"));
    }

    private String firstFreeSlotStart() throws Exception {
        MvcResult result = mockMvc.perform(post(BASE + "/searchTimeSlot"))
                .andExpect(status().isCreated())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(),
                "$.availableTimeSlot[0].validFor.startDateTime");
    }

    private String book(String sub, String start) throws Exception {
        String end = java.time.OffsetDateTime.parse(start).plusHours(2).toString();
        MvcResult result = mockMvc.perform(post(BASE + "/appointment").with(customer(sub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"validFor": {"startDateTime": "%s", "endDateTime": "%s"},
                                 "description": "Fiber install",
                                 "relatedEntity": [{"id": "order-1", "@referredType": "ProductOrder"}],
                                 "place": {"postCode": "11122", "city": "Stockholm"}}
                                """.formatted(start, end)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("confirmed"))
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    @Test
    void slotsAreAnonymous_bookingFillsThem_andFullSlotConflicts() throws Exception {
        String start = firstFreeSlotStart();

        book("cust-a1", start);
        book("cust-a2", start);
        book("cust-a3", start); // capacity 3

        String end = java.time.OffsetDateTime.parse(start).plusHours(2).toString();
        mockMvc.perform(post(BASE + "/appointment").with(customer("cust-a4"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"validFor\": {\"startDateTime\": \"%s\", \"endDateTime\": \"%s\"}}"
                                .formatted(start, end)))
                .andExpect(status().isConflict());

        // The filled slot vanished from the search results.
        MvcResult search = mockMvc.perform(post(BASE + "/searchTimeSlot"))
                .andExpect(status().isCreated()).andReturn();
        if (search.getResponse().getContentAsString().contains("\"startDateTime\":\"" + start + "\"")) {
            throw new AssertionError("fully booked slot still advertised");
        }
    }

    @Test
    void customersAreIsolated_andCancellingFreesTheSlot() throws Exception {
        String start = firstFreeSlotStart();
        String id = book("cust-b1", start);

        mockMvc.perform(get(BASE + "/appointment/" + id).with(customer("cust-nosy")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(BASE + "/appointment/" + id).with(customer("cust-b1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relatedEntity[0].id").value("order-1"))
                .andExpect(jsonPath("$.place.city").value("Stockholm"));

        mockMvc.perform(patch(BASE + "/appointment/" + id).with(customer("cust-b1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"cancelled\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"));

        // Cancelling twice conflicts; the slot itself is free again.
        mockMvc.perform(patch(BASE + "/appointment/" + id).with(customer("cust-b1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"cancelled\"}"))
                .andExpect(status().isConflict());
    }
}
