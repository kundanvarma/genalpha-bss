package com.bss.stock;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StockApiTest {

    private static final String BASE = "/tmf-api/productStockManagement/v4";

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor writeToken() {
        return jwt().authorities(new SimpleGrantedAuthority("stock:write"));
    }

    private String createStock(String offeringId, int amount) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE + "/productStock").with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Stock %s", "productOffering": {"id": "%s", "name": "Phone"},
                                 "stockedQuantity": {"amount": %d, "units": "unit"}}
                                """.formatted(offeringId, offeringId, amount)))
                .andExpect(status().isCreated())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    @Test
    void stockIsReadableAnonymously_writesAreNot() throws Exception {
        createStock("po-anon", 5);
        mockMvc.perform(get(BASE + "/productStock?productOfferingId=po-anon"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].availableQuantity.amount").value(5));
        mockMvc.perform(post(BASE + "/productStock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"x\", \"stockedQuantity\": {\"amount\": 1}}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reserve_thenConsume_decrementsTheShelf() throws Exception {
        String stockId = createStock("po-consume", 10);

        mockMvc.perform(post(BASE + "/reserveProductStock").with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productOffering": {"id": "po-consume"}, "quantity": 4,
                                 "relatedOrder": {"id": "order-1"}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("reserved"));

        // Reserved but not consumed: shelf unchanged, availability down.
        mockMvc.perform(get(BASE + "/productStock/" + stockId))
                .andExpect(jsonPath("$.stockedQuantity.amount").value(10))
                .andExpect(jsonPath("$.reservedQuantity.amount").value(4))
                .andExpect(jsonPath("$.availableQuantity.amount").value(6));

        mockMvc.perform(post(BASE + "/consumeProductStock").with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"relatedOrder\": {\"id\": \"order-1\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservations").value(1));

        mockMvc.perform(get(BASE + "/productStock/" + stockId))
                .andExpect(jsonPath("$.stockedQuantity.amount").value(6))
                .andExpect(jsonPath("$.availableQuantity.amount").value(6));
    }

    @Test
    void reserve_beyondAvailability_conflicts_andReleaseRestores() throws Exception {
        String stockId = createStock("po-scarce", 3);

        mockMvc.perform(post(BASE + "/reserveProductStock").with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productOffering": {"id": "po-scarce"}, "quantity": 2,
                                 "relatedOrder": {"id": "order-2"}}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post(BASE + "/reserveProductStock").with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productOffering": {"id": "po-scarce"}, "quantity": 2,
                                 "relatedOrder": {"id": "order-3"}}
                                """))
                .andExpect(status().isConflict());

        mockMvc.perform(post(BASE + "/releaseProductStock").with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"relatedOrder\": {\"id\": \"order-2\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservations").value(1));

        mockMvc.perform(get(BASE + "/productStock/" + stockId))
                .andExpect(jsonPath("$.availableQuantity.amount").value(3));
    }

    @Test
    void reserve_unmanagedOffering_isANoOp() throws Exception {
        mockMvc.perform(post(BASE + "/reserveProductStock").with(writeToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productOffering": {"id": "po-nonexistent"}, "quantity": 99,
                                 "relatedOrder": {"id": "order-4"}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("notManaged"));
    }
}
