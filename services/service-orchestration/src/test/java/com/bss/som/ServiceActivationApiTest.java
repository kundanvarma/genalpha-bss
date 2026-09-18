package com.bss.som;

import com.bss.som.api.ApiConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TMF640: the activation face is the SAME inventory seen from the activation
 * side — a POSTed service shows up under TMF638 with the inventory href, the
 * activation face answers with its own href, fields=/filters/404/monitor
 * behave as the kit expects, and no customer token can declare a service.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ServiceActivationApiTest {

    private static final String BASE = ApiConstants.ACTIVATION_BASE;

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper json = new ObjectMapper();

    private static RequestPostProcessor staff() {
        return jwt().authorities(
                new SimpleGrantedAuthority("service:read"),
                new SimpleGrantedAuthority("service:write"));
    }

    private static RequestPostProcessor customer(String sub) {
        return jwt().jwt(j -> j.subject(sub)).authorities(new SimpleGrantedAuthority("customer"));
    }

    @Test
    void activate_thenReadThroughBothFaces_filtersFieldsMonitorAnd404() throws Exception {
        MvcResult created = mockMvc.perform(post(BASE + "/service").with(staff())
                        .contentType("application/json")
                        .content("{\"serviceSpecification\":{\"id\":\"cfs45\"},\"state\":\"active\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("monitorId"))
                .andExpect(jsonPath("$.href").value(startsWith(BASE + "/service/")))
                .andExpect(jsonPath("$.serviceDate").isString())
                .andExpect(jsonPath("$.serviceSpecification.id").value("cfs45"))
                .andExpect(jsonPath("$.state").value("active"))
                .andExpect(jsonPath("$.name").value("cfs45"))
                .andExpect(jsonPath("$['@type']").value("Service"))
                .andReturn();
        JsonNode body = json.readTree(created.getResponse().getContentAsString());
        String id = body.get("id").asText();
        String serviceDate = body.get("serviceDate").asText();
        String monitorId = created.getResponse().getHeader("monitorId");

        // the activation face, by id and by filter
        mockMvc.perform(get(BASE + "/service/" + id).with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.serviceDate").value(serviceDate));
        mockMvc.perform(get(BASE + "/service").param("id", id).with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(id));
        mockMvc.perform(get(BASE + "/service").param("serviceDate", serviceDate).with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id));

        // fields=: only the asked-for attributes plus id
        MvcResult slim = mockMvc.perform(get(BASE + "/service").param("fields", "state")
                        .param("id", id).with(staff()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode row = json.readTree(slim.getResponse().getContentAsString()).get(0);
        assertThat(row.size()).isEqualTo(2);
        assertThat(row.get("state").asText()).isEqualTo("active");

        // the same row through the inventory face, with ITS href
        mockMvc.perform(get(ApiConstants.INVENTORY_BASE + "/service/" + id).with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.href").value(ApiConstants.INVENTORY_BASE + "/service/" + id))
                .andExpect(jsonPath("$.name").value("cfs45"));

        // the monitor born with the activation
        mockMvc.perform(get(BASE + "/monitor/" + monitorId).with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("Completed"))
                .andExpect(jsonPath("$.response.statusCode").value(201))
                .andExpect(jsonPath("$.service.id").value(id));

        mockMvc.perform(get(BASE + "/service/55ba5a5c-ff73-44f0-8c48-398b35aecd49").with(staff()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(BASE + "/monitor/nope").with(staff()))
                .andExpect(status().isNotFound());
    }

    @Test
    void callerDocumentIsEchoed_andCustomersCannotDeclareServices() throws Exception {
        mockMvc.perform(post(BASE + "/service").with(staff())
                        .contentType("application/json")
                        .content("""
                                {"name":"vCPE serial 1","description":"Instantiation of vCPE",
                                 "state":"feasibilityChecked","category":"CFS",
                                 "serviceDate":"2018-01-15T12:26:11.747Z",
                                 "serviceSpecification":{"id":"1212","href":"/spec/1212","version":"1.0.0"},
                                 "serviceCharacteristic":[{"name":"vCPE_IP","valueType":"object",
                                   "value":{"@type":"IPAddress","address":"193.218.236.21"}}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("feasibilityChecked"))
                .andExpect(jsonPath("$.category").value("CFS"))
                .andExpect(jsonPath("$.serviceDate").value("2018-01-15T12:26:11.747Z"))
                .andExpect(jsonPath("$.serviceCharacteristic[0].value.address").value("193.218.236.21"))
                .andExpect(jsonPath("$.serviceSpecification.version").value("1.0.0"))
                .andExpect(jsonPath("$.serviceRelationship").isArray())
                .andExpect(jsonPath("$.relatedParty").isArray());

        mockMvc.perform(get(BASE + "/service").param("state", "feasibilityChecked")
                        .param("fields", "id,href").with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].href").value(startsWith(BASE + "/service/")))
                .andExpect(jsonPath("$[0].state").doesNotExist());

        mockMvc.perform(post(BASE + "/service").with(customer("cust-1"))
                        .contentType("application/json")
                        .content("{\"serviceSpecification\":{\"id\":\"cfs45\"},\"state\":\"active\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(BASE + "/service").with(staff())
                        .contentType("application/json")
                        .content("{\"state\":\"active\"}"))
                .andExpect(status().isBadRequest());
    }
}
