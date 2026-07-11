package com.bss.assurance;

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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssuranceApiTest {

    private static final String ALARM = "/tmf-api/alarmManagement/v4";
    private static final String PROBLEM = "/tmf-api/serviceProblemManagement/v4";

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor noc() {
        return jwt().authorities(
                new SimpleGrantedAuthority("assurance:read"),
                new SimpleGrantedAuthority("assurance:write"));
    }

    private void alarm(String object, String severity) throws Exception {
        mockMvc.perform(post(ALARM + "/alarm").with(noc())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"alarmedObject": "%s", "perceivedSeverity": "%s",
                                 "probableCause": "fiber cut at %s"}
                                """.formatted(object, severity, object)))
                .andExpect(status().isCreated());
    }

    @Test
    void criticalAlarmsOpenOneProblem_resolutionClearsTheAlarms() throws Exception {
        alarm("olt-district-9", "minor");     // noise: no problem
        alarm("olt-district-9", "critical");  // opens the problem
        alarm("olt-district-9", "critical");  // joins it — still one problem

        MvcResult open = mockMvc.perform(get(PROBLEM + "/serviceProblem").param("status", "open").with(noc()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Outage: olt-district-9"))
                .andReturn();
        String problemId = JsonPath.read(open.getResponse().getContentAsString(), "$[0].id");

        mockMvc.perform(patch(PROBLEM + "/serviceProblem/" + problemId).with(noc())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"resolved\"}"))
                .andExpect(jsonPath("$.status").value("resolved"));

        mockMvc.perform(get(PROBLEM + "/serviceProblem").param("status", "open").with(noc()))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get(ALARM + "/alarm").param("state", "raised").with(noc()))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void assuranceRequiresTheRole() throws Exception {
        mockMvc.perform(get(PROBLEM + "/serviceProblem"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(PROBLEM + "/serviceProblem")
                        .with(jwt().authorities(new SimpleGrantedAuthority("customer"))))
                .andExpect(status().isForbidden());
    }
}
