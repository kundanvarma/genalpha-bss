package com.bss.stock;

import com.bss.stock.dto.EntityRef;
import com.bss.stock.dto.NameValue;
import com.bss.stock.dto.ProductRef;
import com.bss.stock.dto.ProductStockView;
import com.bss.stock.dto.Quantity;
import com.bss.stock.dto.ReserveProductStockView;
import com.bss.stock.dto.StockOperationDto;
import com.bss.stock.dto.TaskReceipt;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure Jackson: the bytes and the key order of every product-stock wire record. */
class DtoRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private String write(Object o) throws Exception {
        return mapper.writeValueAsString(o);
    }

    @Test
    void aStockRowDeclaresWhatTheServerOwnsAndRoundTripsTheRest() throws Exception {
        Map<String, com.fasterxml.jackson.databind.JsonNode> rest = new LinkedHashMap<>();
        rest.put("place", mapper.readTree("[{\"name\":\"Warehouse 1\"}]"));
        rest.put("validFor", mapper.readTree("{\"startDateTime\":\"2026-01-01\"}"));
        ProductStockView v = new ProductStockView("s-1", "/tmf-api/productStockManagement/v4/productStock/s-1",
                "iPhone 17 black 256GB", new Quantity(10, "unit"), new Quantity(3, "unit"),
                new Quantity(7, "unit"), mapper.readTree("{\"amount\":10}"),
                mapper.readTree("\"available\""),
                mapper.readTree("{\"id\":\"off-1\"}"),
                OffsetDateTime.parse("2026-09-23T08:00:00Z"), "ProductStock", rest);
        assertThat(write(v)).isEqualTo("{\"id\":\"s-1\","
                + "\"href\":\"/tmf-api/productStockManagement/v4/productStock/s-1\","
                + "\"name\":\"iPhone 17 black 256GB\","
                + "\"stockedQuantity\":{\"amount\":10,\"units\":\"unit\"},"
                + "\"reservedQuantity\":{\"amount\":3,\"units\":\"unit\"},"
                + "\"availableQuantity\":{\"amount\":7,\"units\":\"unit\"},"
                + "\"productStockLevel\":{\"amount\":10},\"productStockStatusType\":\"available\","
                + "\"stockedProduct\":{\"id\":\"off-1\"},\"lastUpdate\":\"2026-09-23T08:00:00Z\","
                + "\"@type\":\"ProductStock\","
                + "\"place\":[{\"name\":\"Warehouse 1\"}],"
                + "\"validFor\":{\"startDateTime\":\"2026-01-01\"}}");
    }

    @Test
    void anUnnamedRowOmitsItsNameAndKeepsEveryOtherKey() throws Exception {
        ProductStockView v = new ProductStockView("s-2", "/h", null, new Quantity(0, "unit"),
                new Quantity(0, "unit"), new Quantity(0, "unit"), mapper.readTree("{\"amount\":0}"),
                mapper.readTree("\"available\""), mapper.readTree("{}"), null, "ProductStock", null);
        assertThat(write(v)).isEqualTo("{\"id\":\"s-2\",\"href\":\"/h\","
                + "\"stockedQuantity\":{\"amount\":0,\"units\":\"unit\"},"
                + "\"reservedQuantity\":{\"amount\":0,\"units\":\"unit\"},"
                + "\"availableQuantity\":{\"amount\":0,\"units\":\"unit\"},"
                + "\"productStockLevel\":{\"amount\":0},\"productStockStatusType\":\"available\","
                + "\"stockedProduct\":{},\"lastUpdate\":null,\"@type\":\"ProductStock\"}");
    }

    @Test
    void aReservationResourceEchoesItsItemsAndItsState() throws Exception {
        assertThat(write(new ReserveProductStockView("r-1", "/h", "reserved",
                mapper.readTree("[{\"quantity\":2}]"), "ReserveProductStock", null)))
                .isEqualTo("{\"id\":\"r-1\",\"href\":\"/h\",\"reserveProductStockState\":\"reserved\","
                        + "\"reserveProductStockItem\":[{\"quantity\":2}],\"@type\":\"ReserveProductStock\"}");
    }

    @Test
    void aReferenceKeepsThePostedOrderOfItsExtraKeys() throws Exception {
        String posted = "{\"id\":\"off-1\",\"name\":\"iPhone 17\",\"@referredType\":\"ProductOffering\","
                + "\"category\":\"device\"}";
        EntityRef ref = mapper.readValue(posted, EntityRef.class);
        assertThat(ref.id()).isEqualTo("off-1");
        // a creator-bound any-setter would have handed these back last-first
        assertThat(ref.extensions().keySet()).containsExactly("@referredType", "category");
        assertThat(write(ref)).isEqualTo("{\"id\":\"off-1\",\"name\":\"iPhone 17\","
                + "\"@referredType\":\"ProductOffering\",\"category\":\"device\"}");
    }

    @Test
    void aRequestedProductNamesTheVariantAndKeepsTheRest() throws Exception {
        ProductRef p = mapper.readValue("{\"id\":\"off-1\",\"productCharacteristic\":["
                + "{\"name\":\"colour\",\"value\":\"black\"},{\"name\":\"capacity\",\"value\":256}],"
                + "\"note\":\"gift\"}", ProductRef.class);
        assertThat(p.characteristics()).containsExactly(
                Map.entry("colour", "black"), Map.entry("capacity", "256"));
        assertThat(p.extensions().keySet()).containsExactly("note");
        assertThat(write(p)).isEqualTo("{\"id\":\"off-1\",\"productCharacteristic\":["
                + "{\"name\":\"colour\",\"value\":\"black\"},{\"name\":\"capacity\",\"value\":256}],"
                + "\"note\":\"gift\"}");
        assertThat(new ProductRef(null, null, null, null, null).characteristics()).isEmpty();
    }

    @Test
    void theTaskBodyEchoesBackExactlyWhatTheOrderPipelinePosted() throws Exception {
        String posted = "{\"productOffering\":{\"id\":\"off-1\",\"@referredType\":\"ProductOffering\"},"
                + "\"quantity\":2,\"relatedOrder\":{\"id\":\"ord-1\"},"
                + "\"requestedProduct\":{\"productCharacteristic\":[{\"name\":\"colour\",\"value\":\"black\"}]},"
                + "\"state\":\"reserved\"}";
        StockOperationDto dto = mapper.readValue(posted, StockOperationDto.class);
        assertThat(dto.getProductOffering().id()).isEqualTo("off-1");
        assertThat(dto.getRelatedOrder().id()).isEqualTo("ord-1");
        assertThat(dto.getQuantity()).isEqualTo(2);
        assertThat(dto.getRequestedProduct().characteristics()).containsEntry("colour", "black");
        assertThat(write(dto)).isEqualTo(posted);
    }

    @Test
    void theTaskReceiptsKeepTheirKeyOrder() throws Exception {
        assertThat(write(new TaskReceipt("released", 2)))
                .isEqualTo("{\"state\":\"released\",\"reservations\":2}");
        assertThat(write(new TaskReceipt("completed", 0)))
                .isEqualTo("{\"state\":\"completed\",\"reservations\":0}");
        assertThat(write(new Quantity(5, null))).isEqualTo("{\"amount\":5}");
        assertThat(write(new NameValue("colour", "black")))
                .isEqualTo("{\"name\":\"colour\",\"value\":\"black\"}");
    }

    @Test
    void aPostedBodyIsStoredAsTheDocumentItIs() throws Exception {
        ObjectNode body = (ObjectNode) mapper.readTree(
                "{\"name\":\"row\",\"stockedQuantity\":{\"amount\":10,\"units\":\"unit\"},"
                        + "\"productOffering\":{\"id\":\"off-1\"}}");
        assertThat(body.toString()).isEqualTo("{\"name\":\"row\",\"stockedQuantity\":"
                + "{\"amount\":10,\"units\":\"unit\"},\"productOffering\":{\"id\":\"off-1\"}}");
        ObjectNode patch = (ObjectNode) mapper.readTree("{\"name\":\"renamed\",\"place\":[]}");
        body.setAll(patch);
        // an existing key keeps its position, a new one lands at the end — as the map merge did
        assertThat(body.toString()).isEqualTo("{\"name\":\"renamed\",\"stockedQuantity\":"
                + "{\"amount\":10,\"units\":\"unit\"},\"productOffering\":{\"id\":\"off-1\"},"
                + "\"place\":[]}");
    }
}
