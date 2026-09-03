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

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Capacity is DERIVED from the roster, and the calendar is the tenant's:
 * its zone, its working days, its windows. Runs inside tenant-b so the
 * roster never touches the flat-capacity expectations of the other suites.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScheduleRosterTest {

    private static final String BASE = "/tmf-api/appointment/v4";
    private static final String ISSUER_B = "https://idp.tenant-b.test/realms/bss";

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor admin() {
        return jwt().jwt(j -> j.issuer(ISSUER_B)).authorities(
                new SimpleGrantedAuthority("appointment:admin"),
                new SimpleGrantedAuthority("appointment:read"),
                new SimpleGrantedAuthority("appointment:write"));
    }

    private static RequestPostProcessor customer(String sub) {
        return jwt().jwt(j -> j.issuer(ISSUER_B).subject(sub)).authorities(
                new SimpleGrantedAuthority("customer"),
                new SimpleGrantedAuthority("appointment:read"),
                new SimpleGrantedAuthority("appointment:write"));
    }

    private String json(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    private List<String> freeStarts() throws Exception {
        // an authenticated search resolves tenant-b from the issuer
        MvcResult r = mockMvc.perform(post(BASE + "/searchTimeSlot").with(customer("cust-b0")))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(json(r), "$.availableTimeSlot[*].validFor.startDateTime");
    }

    private int bookUntilFull(String start, int max) throws Exception {
        String end = java.time.OffsetDateTime.parse(start).plusHours(2).toString();
        int booked = 0;
        for (int i = 0; i < max; i++) {
            int status = mockMvc.perform(post(BASE + "/appointment").with(customer("cust-b" + i))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"validFor\": {\"startDateTime\": \"%s\", \"endDateTime\": \"%s\"}}"
                            .formatted(start, end)))
                    .andReturn().getResponse().getStatus();
            if (status == 201) {
                booked++;
            } else if (status == 409) {
                break;
            } else {
                throw new AssertionError("unexpected " + status + " booking " + start);
            }
        }
        return booked;
    }

    @Test
    void calendarIsTheTenantsAndCapacityFollowsTheRoster() throws Exception {
        // customers cannot see or edit the operator's calendar
        mockMvc.perform(get(BASE + "/technician").with(customer("cust-x"))).andExpect(status().isForbidden());
        mockMvc.perform(put(BASE + "/scheduleConfig").with(customer("cust-x"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());

        // the operator schedules in Georgetown: Mon-Sat, three windows a day
        mockMvc.perform(put(BASE + "/scheduleConfig").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timezone": "America/Guyana", "workingDays": ["MON","TUE","WED","THU","FRI","SAT"],
                                 "slotStarts": ["08:00","10:00","13:00"], "slotHours": 2, "daysAhead": 6}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("America/Guyana"))
                .andExpect(jsonPath("$.capacityMode").value("flat"));
        mockMvc.perform(put(BASE + "/scheduleConfig").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"timezone\": \"Mars/Olympus\"}"))
                .andExpect(status().isBadRequest());

        List<String> starts = freeStarts();
        if (starts.isEmpty()) {
            throw new AssertionError("no windows offered");
        }
        // every offered window carries Guyana's offset and one of the configured starts
        for (String s : starts) {
            if (!s.endsWith("-04:00") || !(s.contains("T08:00") || s.contains("T10:00") || s.contains("T13:00"))) {
                throw new AssertionError("window not in the tenant's calendar: " + s);
            }
            if (java.time.OffsetDateTime.parse(s).getDayOfWeek() == java.time.DayOfWeek.SUNDAY) {
                throw new AssertionError("Sunday offered although not a working day: " + s);
            }
        }

        // a roster of two: Asha covers mornings only, Ravi the whole day
        String asha = com.jayway.jsonpath.JsonPath.read(json(mockMvc.perform(post(BASE + "/technician").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Asha Persaud", "zone": "Georgetown", "skills": ["fibre"],
                                 "workingDays": ["MON","TUE","WED","THU","FRI","SAT"], "startTime": "08:00", "endTime": "12:00"}
                                """))
                .andExpect(status().isCreated()).andReturn()), "$.id");
        mockMvc.perform(post(BASE + "/technician").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Ravi Singh", "zone": "East Bank", "skills": ["fibre","mobile"],
                                 "workingDays": ["MON","TUE","WED","THU","FRI","SAT"], "startTime": "08:00", "endTime": "17:00"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(get(BASE + "/scheduleConfig").with(admin()))
                .andExpect(jsonPath("$.capacityMode").value("roster"))
                .andExpect(jsonPath("$.rosterSize").value(2));

        // a morning window (08:00) holds two visits; an afternoon one (13:00) only Ravi's
        String morning = starts.stream().filter(s -> s.contains("T08:00")).findFirst().orElseThrow();
        String afternoon = starts.stream().filter(s -> s.contains("T13:00")).findFirst().orElseThrow();
        MvcResult search = mockMvc.perform(post(BASE + "/searchTimeSlot").with(customer("cust-b0")))
                .andReturn();
        List<Integer> remainingMorning = com.jayway.jsonpath.JsonPath.read(json(search),
                "$.availableTimeSlot[?(@.validFor.startDateTime == '" + morning + "')].remaining");
        List<Integer> remainingAfternoon = com.jayway.jsonpath.JsonPath.read(json(search),
                "$.availableTimeSlot[?(@.validFor.startDateTime == '" + afternoon + "')].remaining");
        if (remainingMorning.get(0) != 2 || remainingAfternoon.get(0) != 1) {
            throw new AssertionError("remaining should follow the roster: " + remainingMorning + " / " + remainingAfternoon);
        }
        if (bookUntilFull(morning, 5) != 2) {
            throw new AssertionError("morning window should hold exactly two visits");
        }
        if (bookUntilFull(afternoon, 5) != 1) {
            throw new AssertionError("afternoon window should hold exactly one visit");
        }

        // Asha goes on leave: the morning window is now Ravi's alone and already full
        mockMvc.perform(patch(BASE + "/technician/" + asha).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\": false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        if (freeStarts().contains(morning)) {
            throw new AssertionError("morning window still offered after its capacity dropped to the booked count");
        }

        // a window nobody works is not bookable at all, even by hand
        String sunday = java.time.LocalDate.now(java.time.ZoneId.of("America/Guyana"))
                .with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.SUNDAY))
                .atTime(9, 0).atZone(java.time.ZoneId.of("America/Guyana")).toOffsetDateTime().toString();
        if (bookUntilFull(sunday, 1) != 0) {
            throw new AssertionError("a Sunday visit was accepted with no technician on shift");
        }

        // booked appointments read back in the tenant's zone
        MvcResult mine = mockMvc.perform(get(BASE + "/appointment").with(customer("cust-b0")))
                .andExpect(status().isOk()).andReturn();
        List<String> starts0 = com.jayway.jsonpath.JsonPath.read(json(mine), "$[*].validFor.startDateTime");
        if (starts0.isEmpty() || !starts0.get(0).endsWith("-04:00")) {
            throw new AssertionError("appointment not rendered in tenant zone: " + starts0);
        }

        mockMvc.perform(delete(BASE + "/technician/" + asha).with(admin())).andExpect(status().isNoContent());
        mockMvc.perform(get(BASE + "/technician/" + asha).with(admin())).andExpect(status().isNotFound());
    }
}
