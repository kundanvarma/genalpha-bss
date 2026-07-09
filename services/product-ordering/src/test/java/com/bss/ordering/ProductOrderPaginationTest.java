package com.bss.ordering;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bss.ordering.client.CatalogClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductOrderPaginationTest {

    private static final String BASE = "/tmf-api/productOrderingManagement/v4/productOrder";

    @Autowired
    private MockMvc mockMvc;

    // Orders in these tests reference offering "po-001"; orchestration validates
    // references against the catalog, which is not running here.
    @MockBean
    private CatalogClient catalogClient;

    @BeforeEach
    void stubCatalog() {
        given(catalogClient.findOffering(anyString()))
                .willReturn(java.util.Optional.of(new CatalogClient.OfferingRef("po-001", "Stub Offering")));
    }

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void pagination_sliceMatchesFullList() throws Exception {
        for (int i = 1; i <= 3; i++) {
            String body = """
                    {
                      "state": "acknowledged",
                      "description": "Pagination test order %d",
                      "productOfferingId": "po-001"
                    }
                    """.formatted(i);
            mockMvc.perform(post(BASE).with(writeToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated());
        }

        MvcResult fullResult = mockMvc.perform(get(BASE).with(readToken())
                        .param("offset", "0")
                        .param("limit", "100")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode fullList = objectMapper.readTree(fullResult.getResponse().getContentAsString());

        MvcResult sliceResult = mockMvc.perform(get(BASE).with(readToken())
                        .param("offset", "1")
                        .param("limit", "2")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode slice = objectMapper.readTree(sliceResult.getResponse().getContentAsString());

        // offset=1&limit=2 is deliberately not offset-aligned: a page-number
        // misreading of offset would return full[2..3], not full[1..2].
        assertEquals(2, slice.size());
        assertEquals(fullList.get(1).get("id").asText(), slice.get(0).get("id").asText());
        assertEquals(fullList.get(2).get("id").asText(), slice.get(1).get("id").asText());
        assertEquals(fullResult.getResponse().getHeader("X-Total-Count"),
                sliceResult.getResponse().getHeader("X-Total-Count"));
        assertTrue(Integer.parseInt(sliceResult.getResponse().getHeader("X-Total-Count")) >= 3);
        assertEquals("2", sliceResult.getResponse().getHeader("X-Result-Count"));
    }

    @Test
    void pagination_rejectsZeroLimit() throws Exception {
        mockMvc.perform(get(BASE).with(readToken())
                        .param("limit", "0")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }

    @Test
    void pagination_rejectsNegativeOffset() throws Exception {
        mockMvc.perform(get(BASE).with(readToken())
                        .param("offset", "-1")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }

    @Test
    void pagination_rejectsLimitOver100() throws Exception {
        mockMvc.perform(get(BASE).with(readToken())
                        .param("limit", "101")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }

    private static RequestPostProcessor readToken() {
        return jwt().authorities(new SimpleGrantedAuthority("ordering:read"));
    }

    private static RequestPostProcessor writeToken() {
        return jwt().authorities(new SimpleGrantedAuthority("ordering:write"));
    }
}
