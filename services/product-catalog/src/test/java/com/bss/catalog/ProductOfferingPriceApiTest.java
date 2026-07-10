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

import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductOfferingPriceApiTest {

    private static final String BASE = "/tmf-api/productCatalogManagement/v4/productOfferingPrice";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createPrice_echoesMoneyAndRecurringPeriod() throws Exception {
        String body = """
                {
                  "name": "Fiber 1000 Monthly",
                  "priceType": "recurring",
                  "price": {"unit": "EUR", "value": 39.99},
                  "recurringChargePeriodType": "month",
                  "recurringChargePeriodLength": 1
                }
                """;

        mockMvc.perform(post(BASE).with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(not(emptyOrNullString())))
                .andExpect(jsonPath("$.price.unit").value("EUR"))
                .andExpect(jsonPath("$.price.value").value(39.99))
                .andExpect(jsonPath("$.recurringChargePeriodType").value("month"))
                .andExpect(jsonPath("$.recurringChargePeriodLength").value(1))
                .andExpect(jsonPath("$.['@type']").value("ProductOfferingPrice"));
    }

    private static RequestPostProcessor writeToken() {
        return jwt().authorities(new SimpleGrantedAuthority("catalog:write"));
    }
}
