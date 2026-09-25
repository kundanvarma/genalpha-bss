package com.bss.catalog;

import com.bss.catalog.dto.ResourceSpecificationDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TMF634 Resource Catalog: a resource specification names the SEAM an adapter
 * provides (never a vendor); browse is public, authoring needs catalog:write;
 * the wire shape round-trips with the open characteristic block intact.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResourceCatalogApiTest {

    private static final String V4 = "/tmf-api/resourceCatalogManagement/v4";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void resourceSpecification_createReadPatchDelete_onTheStandardPath() throws Exception {
        String created = mockMvc.perform(post(V4 + "/resourceSpecification").with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Number from pool", "category": "logical", "version": "1.0",
                                 "resourceSpecCharacteristic": [{"name": "seam", "valueType": "string",
                                   "resourceSpecCharacteristicValue": [{"value": "number", "isDefault": true}]}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.@type").value("LogicalResourceSpecification"))
                .andExpect(jsonPath("$.@baseType").value("ResourceSpecification"))
                .andExpect(jsonPath("$.@schemaLocation").isString())
                .andExpect(jsonPath("$.lifecycleStatus").value("Active"))
                .andExpect(jsonPath("$.isBundle").value(false))
                .andExpect(jsonPath("$.resourceSpecCharacteristic[0].name").value("seam"))
                .andReturn().getResponse().getContentAsString();
        JsonNode spec = objectMapper.readTree(created);
        String id = spec.get("id").asText();
        assertThat(spec.get("href").asText()).isEqualTo(V4 + "/resourceSpecification/" + id);

        // browse is public: no token on the reads
        mockMvc.perform(get(V4 + "/resourceSpecification/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Number from pool"))
                .andExpect(jsonPath("$.resourceSpecCharacteristic[0].resourceSpecCharacteristicValue[0].value").value("number"));
        mockMvc.perform(get(V4 + "/resourceSpecification?category=logical&fields=name,category"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Number from pool"))
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].lifecycleStatus").doesNotExist());

        // authoring is not: anonymous writes are refused
        mockMvc.perform(patch(V4 + "/resourceSpecification/" + id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"renamed\"}"))
                .andExpect(status().isUnauthorized());
        // id is never patchable (TMF630)
        mockMvc.perform(patch(V4 + "/resourceSpecification/" + id).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"id\": \"other\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch(V4 + "/resourceSpecification/" + id).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"renamed\", \"lifecycleStatus\": \"Retired\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("renamed"))
                .andExpect(jsonPath("$.lifecycleStatus").value("Retired"));

        mockMvc.perform(delete(V4 + "/resourceSpecification/" + id).with(writeToken()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(V4 + "/resourceSpecification/" + id))
                .andExpect(status().isNotFound());
    }

    @Test
    void resourceSpecification_dtoRoundTrips_withTheOpenBlocksIntact() throws Exception {
        String wire = "{\"@type\":\"PhysicalResourceSpecification\",\"name\":\"Router\",\"isBundle\":false,"
                + "\"resourceSpecCharacteristic\":[{\"name\":\"seam\",\"resourceSpecCharacteristicValue\":[{\"value\":\"cpe\"}]}],"
                + "\"resourceSpecRelationship\":[{\"relationshipType\":\"dependency\",\"id\":\"rs-1\"}]}";
        ResourceSpecificationDto dto = objectMapper.readValue(wire, ResourceSpecificationDto.class);
        assertThat(dto.getType()).isEqualTo("PhysicalResourceSpecification");
        assertThat(dto.getResourceSpecCharacteristic()).hasSize(1);
        assertThat(dto.getResourceSpecRelationship().get(0).get("relationshipType")).isEqualTo("dependency");
        JsonNode back = objectMapper.readTree(objectMapper.writeValueAsString(dto));
        assertThat(back.get("@type").asText()).isEqualTo("PhysicalResourceSpecification");
        assertThat(back.get("resourceSpecCharacteristic").get(0).get("resourceSpecCharacteristicValue").get(0).get("value").asText()).isEqualTo("cpe");
        assertThat(back.has("id")).isFalse(); // NON_NULL: nothing invented on the way back
    }

    private static RequestPostProcessor writeToken() {
        return jwt().authorities(new SimpleGrantedAuthority("catalog:write"));
    }
}
