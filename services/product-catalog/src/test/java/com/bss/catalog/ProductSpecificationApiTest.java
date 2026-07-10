package com.bss.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductSpecificationApiTest {

    private static final String BASE = "/tmf-api/productCatalogManagement/v4/productSpecification";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createSpec_echoesVariantCharacteristics() throws Exception {
        String body = """
                {
                  "name": "Phone Spec",
                  "brand": "Acme",
                  "productSpecCharacteristic": [
                    {"name": "color", "valueType": "string", "productSpecCharacteristicValue":
                      [{"value": "Black"}, {"value": "Blue"}]},
                    {"name": "storage", "valueType": "string", "productSpecCharacteristicValue":
                      [{"value": "256GB"}, {"value": "512GB"}]}
                  ]
                }
                """;

        mockMvc.perform(post(BASE).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productSpecCharacteristic.length()").value(2))
                .andExpect(jsonPath("$.productSpecCharacteristic[0].name").value("color"))
                .andExpect(jsonPath("$.productSpecCharacteristic[0].productSpecCharacteristicValue[1].value")
                        .value("Blue"));
    }

    private static RequestPostProcessor writeToken() {
        return jwt().authorities(new SimpleGrantedAuthority("catalog:write"));
    }
}
