package com.bss.inventory;

import com.bss.inventory.dto.ComponentDescriptor;
import com.bss.inventory.dto.ProductDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure Jackson: the bytes and the key order of product-inventory's wire
 * shapes. The three TMF reference blocks are the caller's own documents —
 * the test's job is to prove they come back exactly as they were posted.
 */
class DtoRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private String write(Object o) throws Exception {
        return mapper.writeValueAsString(o);
    }

    @Test
    void aReferenceBlockKeepsTheCallersOwnKeyOrder() throws Exception {
        // a record with declared id/href/name would have re-ordered this
        String posted = "{\"name\":\"Fiber 500\",\"@referredType\":\"ProductOffering\","
                + "\"id\":\"off-1\"}";
        ProductDto dto = new ProductDto();
        dto.setId("p-1");
        dto.setName("Fiber at home");
        dto.setProductOffering(mapper.readTree(posted));
        assertThat(write(dto)).isEqualTo("{\"id\":\"p-1\",\"name\":\"Fiber at home\","
                + "\"productOffering\":" + posted + ",\"@type\":\"Product\"}");
    }

    @Test
    void theThreeBlocksAreLeftOffWhenTheColumnIsNull() throws Exception {
        ProductDto dto = new ProductDto();
        dto.setId("p-1");
        dto.setName("Fiber at home");
        dto.setStatus("active");
        dto.setProductCharacteristic(List.of());
        dto.setProductPrice(List.of());
        dto.setRelatedParty(List.of());
        assertThat(write(dto)).isEqualTo("{\"id\":\"p-1\",\"name\":\"Fiber at home\","
                + "\"status\":\"active\",\"productCharacteristic\":[],\"productPrice\":[],"
                + "\"relatedParty\":[],\"@type\":\"Product\"}");
    }

    @Test
    void theHouseKeysStillLeadTheDocumentAsTheDeclarationOrderSays() throws Exception {
        ProductDto dto = new ProductDto();
        dto.setPreviousOffering(mapper.readTree("{\"id\":\"off-0\"}"));
        dto.setOfferingChangedAt("2026-09-23T08:00Z");
        dto.setStartDate("2026-01-01T00:00Z");
        dto.setTerminationDate("2026-12-31T00:00Z");
        dto.setId("p-1");
        dto.setName("Fiber at home");
        dto.setBillingAccount(mapper.readTree("{\"id\":\"ba-1\",\"name\":\"Household\"}"));
        assertThat(write(dto)).isEqualTo("{\"previousOffering\":{\"id\":\"off-0\"},"
                + "\"offeringChangedAt\":\"2026-09-23T08:00Z\","
                + "\"startDate\":\"2026-01-01T00:00Z\","
                + "\"terminationDate\":\"2026-12-31T00:00Z\",\"id\":\"p-1\","
                + "\"name\":\"Fiber at home\","
                + "\"billingAccount\":{\"id\":\"ba-1\",\"name\":\"Household\"},"
                + "\"@type\":\"Product\"}");
    }

    @Test
    void aPostedBodyBindsTheBlocksAsTreesAndNothingElseChanges() throws Exception {
        ProductDto dto = mapper.readValue("{\"name\":\"Fiber at home\",\"status\":\"active\","
                + "\"productOffering\":{\"id\":5,\"name\":\"Fiber 500\"},"
                + "\"relatedParty\":[{\"id\":\"party-1\",\"role\":\"customer\"}]}",
                ProductDto.class);
        assertThat(dto.getProductOffering().get("id").asText()).isEqualTo("5");
        assertThat(dto.getBillingAccount()).isNull();
        assertThat(dto.getRelatedParty()).hasSize(1);
        // the map read both ids through String.valueOf, so 5 and "5" were one id
        assertThat(mapper.readTree("{\"id\":\"5\"}").get("id").asText())
                .isEqualTo(dto.getProductOffering().get("id").asText());
    }

    @Test
    void theSelectorStillSeesTheBlocksAsPlainNestedMaps() throws Exception {
        ProductDto dto = new ProductDto();
        dto.setId("p-1");
        dto.setName("Fiber at home");
        dto.setProductOffering(mapper.readTree("{\"id\":\"off-1\",\"name\":\"Fiber 500\"}"));
        @SuppressWarnings("unchecked")
        Map<String, Object> full = mapper.convertValue(dto, Map.class);
        assertThat(full.get("productOffering")).isInstanceOf(Map.class);
        assertThat(write(full.get("productOffering")))
                .isEqualTo("{\"id\":\"off-1\",\"name\":\"Fiber 500\"}");
    }

    @Test
    void theComponentDescribesItselfInTheOrderTheRegistryReads() throws Exception {
        assertThat(write(ComponentDescriptor.of("product-inventory", "Holds every product.",
                List.of("Subscription"), List.of("ProductCreateEvent"), "bss.inventory.events",
                List.of("[GET] /tmf-api/productInventory/v4/product"))))
                .isEqualTo("{\"component\":\"product-inventory\","
                        + "\"meaning\":\"Holds every product.\",\"manages\":[\"Subscription\"],"
                        + "\"events\":[\"ProductCreateEvent\"],\"topic\":\"bss.inventory.events\","
                        + "\"routes\":[\"[GET] /tmf-api/productInventory/v4/product\"],"
                        + "\"@type\":\"GenAlphaComponent\"}");
    }
}
