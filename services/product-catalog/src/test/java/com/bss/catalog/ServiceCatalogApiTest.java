package com.bss.catalog;

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
 * TMF633 Service Catalog: the v4 face, its R18 (v3) alias, and the three
 * resources the CTK exercises beside ServiceSpecification — serviceCandidate,
 * importJob, exportJob.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ServiceCatalogApiTest {

    private static final String V4 = "/tmf-api/serviceCatalogManagement/v4";
    private static final String V3 = "/tmf-api/serviceCatalogManagement/v3";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void serviceSpecification_isServedOnBothFaces_withTheStandardsPolymorphicType() throws Exception {
        String created = mockMvc.perform(post(V3 + "/serviceSpecification").with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Only Post Mandatory Service", "@type": "ResourceFacingServiceSpecification",
                                 "lifecycleStatus": "In Design", "isBundle": false}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.@type").value("ResourceFacingServiceSpecification"))
                .andExpect(jsonPath("$.@baseType").value("ServiceSpecification"))
                .andExpect(jsonPath("$.@schemaLocation").isString())
                .andExpect(jsonPath("$.serviceType").value("RFS"))
                .andExpect(jsonPath("$.isBundle").value(false))
                .andReturn().getResponse().getContentAsString();
        JsonNode spec = objectMapper.readTree(created);
        String id = spec.get("id").asText();
        // hrefs stay canonical (v4) whichever face created the row
        assertThat(spec.get("href").asText()).isEqualTo(V4 + "/serviceSpecification/" + id);

        // the same row on the v4 face
        mockMvc.perform(get(V4 + "/serviceSpecification/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Only Post Mandatory Service"));

        // fields= keeps id and href beside what was asked for
        mockMvc.perform(get(V3 + "/serviceSpecification?name=Only Post Mandatory Service&fields=name,isBundle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Only Post Mandatory Service"))
                .andExpect(jsonPath("$[0].isBundle").value(false))
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].href").isString())
                .andExpect(jsonPath("$[0].lifecycleStatus").doesNotExist());

        // R18 answers a PATCH with 201, v4 with 200; id is never patchable
        mockMvc.perform(patch(V3 + "/serviceSpecification/" + id).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"updated Spec\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("updated Spec"));
        mockMvc.perform(patch(V4 + "/serviceSpecification/" + id).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\": \"2.0\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("2.0"));
        mockMvc.perform(patch(V3 + "/serviceSpecification/" + id).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"id\": \"1234\"}"))
                .andExpect(status().isBadRequest());

        // a nameless spec is refused
        mockMvc.perform(post(V3 + "/serviceSpecification").with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lifecycleStatus\": \"In Design\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(delete(V3 + "/serviceSpecification/" + id).with(writeToken()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(V4 + "/serviceSpecification/" + id))
                .andExpect(status().isNotFound());
    }

    @Test
    void serviceSpecification_cfsTagReadsAsCustomerFacingType_forTheOrderingFlow() throws Exception {
        mockMvc.perform(post(V4 + "/serviceSpecification").with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"CFS probe\", \"serviceType\": \"CFS\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.serviceType").value("CFS"))
                .andExpect(jsonPath("$.@type").value("CustomerFacingServiceSpecification"))
                .andExpect(jsonPath("$.isBundle").value(false))
                .andExpect(jsonPath("$.lifecycleStatus").value("Active"));
    }

    @Test
    void serviceCandidate_fullLifecycle_onTheV3Alias() throws Exception {
        String created = mockMvc.perform(post(V3 + "/serviceCandidate").with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "TVServiceCandidate", "version": "2.1",
                                 "validFor": {"startDateTime": "2019-01-20T20:00:00.000Z", "endDateTime": "2020-01-20T20:00:00.000Z"},
                                 "category": [{"id": "5980", "name": "TV"}],
                                 "serviceSpecification": {"id": "9600", "name": "TVSpecification"}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("TVServiceCandidate"))
                .andExpect(jsonPath("$.@type").value("ServiceCandidate"))
                .andExpect(jsonPath("$.category[0].name").value("TV"))
                .andExpect(jsonPath("$.serviceSpecification.id").value("9600"))
                .andExpect(jsonPath("$.validFor.startDateTime").isString())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(created).get("id").asText();

        mockMvc.perform(get(V3 + "/serviceCandidate?name=TVServiceCandidate&fields=name,id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].name").value("TVServiceCandidate"))
                .andExpect(jsonPath("$[0].href").isString())
                .andExpect(jsonPath("$[0].version").doesNotExist());

        mockMvc.perform(patch(V3 + "/serviceCandidate/" + id).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Updated Service Candidate\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Updated Service Candidate"));
        mockMvc.perform(patch(V3 + "/serviceCandidate/" + id).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"id\": \"12312321\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(V3 + "/serviceCandidate").with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\": \"2.1\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(delete(V3 + "/serviceCandidate/" + id).with(writeToken()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(V3 + "/serviceCandidate/" + id))
                .andExpect(status().isNotFound());
    }

    @Test
    void importAndExportJobs_areRecordedAsRequests_notRun() throws Exception {
        String created = mockMvc.perform(post(V3 + "/importJob").with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\": \"https://my-platform/daily/job/NHCFD6\", \"path\": \"/warning/system\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url").value("https://my-platform/daily/job/NHCFD6"))
                .andExpect(jsonPath("$.status").value("Not Started"))
                .andExpect(jsonPath("$.@type").value("ImportJob"))
                .andExpect(jsonPath("$.creationDate").isString())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(created).get("id").asText();

        mockMvc.perform(get(V3 + "/importJob?url=https://my-platform/daily/job/NHCFD6&fields=url"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].url").value("https://my-platform/daily/job/NHCFD6"))
                .andExpect(jsonPath("$[0].href").isString())
                .andExpect(jsonPath("$[0].status").doesNotExist());

        // an export job is a different resource: the import id is unknown there
        mockMvc.perform(get(V3 + "/exportJob/" + id))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(V3 + "/exportJob").with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\": \"https://my-platform/daily/job/EHCFD6\", \"query\": \"lifecycleStatus=Active\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.@type").value("ExportJob"))
                .andExpect(jsonPath("$.query").value("lifecycleStatus=Active"));

        // a job without a url is no job
        mockMvc.perform(post(V3 + "/importJob").with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"path\": \"/warning/system\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(delete(V4 + "/importJob/" + id).with(writeToken()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(V4 + "/importJob/" + id))
                .andExpect(status().isNotFound());
    }

    @Test
    void v3Alias_hasTheSameDoors_asV4() throws Exception {
        mockMvc.perform(get(V3 + "/serviceCandidate"))
                .andExpect(status().isOk());
        mockMvc.perform(post(V3 + "/serviceCandidate")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"no token\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(V3 + "/importJob")
                        .with(jwt().authorities(new SimpleGrantedAuthority("catalog:read")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\": \"https://x\"}"))
                .andExpect(status().isForbidden());
    }

    private static RequestPostProcessor writeToken() {
        return jwt().authorities(new SimpleGrantedAuthority("catalog:write"));
    }
}
