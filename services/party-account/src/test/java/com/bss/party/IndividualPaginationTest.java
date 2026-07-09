package com.bss.party;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IndividualPaginationTest {

    private static final String INDIVIDUAL_BASE = "/tmf-api/party/v4/individual";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void pagination_sliceMatchesFullList() throws Exception {
        for (int i = 0; i < 3; i++) {
            String body = """
                    {
                      "givenName": "Page%d",
                      "familyName": "Doe"
                    }
                    """.formatted(i);
            mockMvc.perform(post(INDIVIDUAL_BASE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated());
        }

        MvcResult fullResult = mockMvc.perform(get(INDIVIDUAL_BASE + "?offset=0&limit=100")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode fullList = objectMapper.readTree(fullResult.getResponse().getContentAsString());

        MvcResult sliceResult = mockMvc.perform(get(INDIVIDUAL_BASE + "?offset=1&limit=2")
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
        mockMvc.perform(get(INDIVIDUAL_BASE + "?limit=0").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }

    @Test
    void pagination_rejectsNegativeOffset() throws Exception {
        mockMvc.perform(get(INDIVIDUAL_BASE + "?offset=-1").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }

    @Test
    void pagination_rejectsLimitOver100() throws Exception {
        mockMvc.perform(get(INDIVIDUAL_BASE + "?limit=101").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }
}
