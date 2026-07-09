package com.bss.ordering;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductOrderApiTest {

    private static final String BASE = "/tmf-api/productOrderingManagement/v4/productOrder";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createProductOrder_returns201WithGeneratedId() throws Exception {
        String body = """
                {
                  "state": "acknowledged",
                  "description": "New mobile subscription order",
                  "productOfferingId": "po-001"
                }
                """;

        mockMvc.perform(post(BASE).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(not(emptyOrNullString())))
                .andExpect(jsonPath("$.href").value(not(emptyOrNullString())))
                .andExpect(jsonPath("$.state").value("acknowledged"))
                .andExpect(jsonPath("$.productOfferingId").value("po-001"))
                .andExpect(jsonPath("$.['@type']").value("ProductOrder"));
    }

    @Test
    void listProductOrders_returns200() throws Exception {
        mockMvc.perform(get(BASE).with(readToken()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    private static RequestPostProcessor readToken() {
        return jwt().authorities(new SimpleGrantedAuthority("ordering:read"));
    }

    private static RequestPostProcessor writeToken() {
        return jwt().authorities(new SimpleGrantedAuthority("ordering:write"));
    }
}
