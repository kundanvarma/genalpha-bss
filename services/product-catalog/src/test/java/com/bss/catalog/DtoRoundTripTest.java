package com.bss.catalog;

import com.bss.catalog.dto.Availability;
import com.bss.catalog.dto.ComputedProductConfigurationItem;
import com.bss.catalog.dto.EntityRef;
import com.bss.catalog.dto.EnvelopeRef;
import com.bss.catalog.dto.GovernanceRequest;
import com.bss.catalog.dto.GovernanceState;
import com.bss.catalog.dto.LaunchDecision;
import com.bss.catalog.dto.LedgerLine;
import com.bss.catalog.dto.Money;
import com.bss.catalog.dto.ProductConfigurationRequest;
import com.bss.catalog.dto.ProductOfferingPriceDto;
import com.bss.catalog.dto.ReadinessItem;
import com.bss.catalog.dto.TimePeriod;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wire records round-trip: known fields typed, unknown fields kept, absent
 * fields absent, numbers as written, keys in the order the maps put them.
 * Pure Jackson, configured as Spring Boot configures it — no context, no database.
 */
class DtoRoundTripTest {

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void money_keepsExtensionsAndOmitsAbsent() throws Exception {
        Money m = json.readValue("{\"unit\":\"NOK\",\"value\":49.90,\"@type\":\"Money\"}", Money.class);
        assertEquals("NOK", m.unit());
        assertEquals(new BigDecimal("49.90"), m.value());
        assertEquals("Money", m.extensions().get("@type"));
        assertEquals("{\"unit\":\"NOK\",\"value\":49.90,\"@type\":\"Money\"}", json.writeValueAsString(m));
        assertEquals("{\"value\":20}", json.writeValueAsString(new Money(null, BigDecimal.valueOf(20))));
        assertEquals("{\"unit\":\"EUR\",\"value\":5,\"note\":\"x\"}",
                json.writeValueAsString(Money.of(java.util.Map.of("unit", "EUR", "value", 5, "note", "x"))));
    }

    @Test
    void entityRef_typedKeysFirstThenExtensions() throws Exception {
        EntityRef r = json.readValue("{\"@referredType\":\"ProductSpecification\",\"custom\":{\"x\":1},\"id\":\"ps-1\",\"name\":\"Fibre\"}", EntityRef.class);
        assertEquals("ps-1", r.id());
        assertEquals("ProductSpecification", r.referredType());
        assertEquals("{\"id\":\"ps-1\",\"name\":\"Fibre\",\"@referredType\":\"ProductSpecification\",\"custom\":{\"x\":1}}",
                json.writeValueAsString(r));
        assertEquals("{\"id\":\"a\",\"name\":\"b\"}", json.writeValueAsString(EntityRef.of("a", "b")));
    }

    @Test
    void timePeriod_isLenientOnTheWayIn() throws Exception {
        TimePeriod w = json.readValue("{\"startDateTime\":\"2026-10-01\",\"endDateTime\":\"2026-12-01T10:00:00+02:00\"}", TimePeriod.class);
        assertEquals(0, w.startDateTime().getHour());
        assertEquals(ZoneOffset.UTC, w.startDateTime().getOffset());
        assertEquals(10, w.endDateTime().getHour());
        assertNull(json.readValue("{\"startDateTime\":null}", TimePeriod.class).startDateTime());
        assertEquals("{\"endDateTime\":\"2026-12-01T10:00:00+02:00\"}",
                json.writeValueAsString(new TimePeriod(null, w.endDateTime())));
    }

    @Test
    void price_roundTripsAsTmf620Wrote() throws Exception {
        String wire = "{\"name\":\"Fiber 1000 Monthly\",\"priceType\":\"recurring\",\"price\":{\"unit\":\"EUR\",\"value\":39.99},"
                + "\"unitOfMeasure\":{\"amount\":12,\"units\":\"month\"},\"validFor\":{\"startDateTime\":\"2026-01-01T00:00:00Z\"},"
                + "\"recurringChargePeriodType\":\"month\"}";
        ProductOfferingPriceDto dto = json.readValue(wire, ProductOfferingPriceDto.class);
        assertEquals(new BigDecimal("39.99"), dto.getPrice().value());
        assertEquals(new BigDecimal("12"), dto.getUnitOfMeasure().amount());
        assertEquals(2026, dto.getValidFor().startDateTime().getYear());
        JsonNode back = json.readTree(json.writeValueAsString(dto));
        assertEquals("{\"unit\":\"EUR\",\"value\":39.99}", back.get("price").toString());
        assertEquals("{\"amount\":12,\"units\":\"month\"}", back.get("unitOfMeasure").toString());
        assertEquals("ProductOfferingPrice", back.get("@type").asText());
        assertFalse(back.has("tax"));
    }

    @Test
    void governanceState_storesOnlyWhatWasSetAndKeepsStrangers() throws Exception {
        GovernanceState g = json.readValue("{\"requestedAt\":\"t1\",\"note\":null,\"readiness\":[{\"owner\":\"campaign:write\",\"label\":\"Campaign\",\"done\":false}],"
                + "\"envelope\":{\"id\":\"r1\",\"name\":\"Inside\"},\"fromTheFuture\":42}", GovernanceState.class);
        assertEquals("t1", g.requestedAt);
        assertNull(g.note);
        assertEquals("campaign:write", g.readiness.get(0).owner());
        assertEquals("Inside", g.envelope.name());
        g.approvedAt = "t2";
        assertEquals("{\"requestedAt\":\"t1\",\"readiness\":[{\"owner\":\"campaign:write\",\"label\":\"Campaign\",\"done\":false}],"
                + "\"approvedAt\":\"t2\",\"envelope\":{\"id\":\"r1\",\"name\":\"Inside\"},\"fromTheFuture\":42}", json.writeValueAsString(g));
        GovernanceState copy = json.convertValue(g, GovernanceState.class);
        copy.readiness = null;
        assertEquals("t2", copy.approvedAt);
        assertFalse(json.writeValueAsString(copy).contains("readiness"));
    }

    @Test
    void launchDecision_unwrapsTheStateInPlaceWithoutDuplicateKeys() throws Exception {
        GovernanceState g = new GovernanceState();
        g.requestedAt = "t1";
        g.requestedBy = "sigrid";
        g.envelope = new EnvelopeRef("r1", "Inside");
        LaunchDecision v = new LaunchDecision("po-1", "Fibre", "Active", null, null, "approved", "envelope", null, "t9",
                List.of(EntityRef.of("web", "Web shop")), "t5", g, List.of(ReadinessItem.open("campaign:write", "Campaign")),
                List.of(new LedgerLine("requested", "sigrid", null, null, null, "t1")), false);
        String out = json.writeValueAsString(v);
        assertEquals("{\"id\":\"po-1\",\"name\":\"Fibre\",\"lifecycleStatus\":\"Active\",\"validFrom\":null,\"validTo\":null,"
                + "\"governanceState\":\"approved\",\"mode\":\"envelope\",\"holdUntil\":null,\"approvalExpiresAt\":\"t9\","
                + "\"channel\":[{\"id\":\"web\",\"name\":\"Web shop\"}],\"lastUpdate\":\"t5\","
                + "\"requestedAt\":\"t1\",\"requestedBy\":\"sigrid\",\"envelope\":{\"id\":\"r1\",\"name\":\"Inside\"},"
                + "\"readiness\":[{\"owner\":\"campaign:write\",\"label\":\"Campaign\",\"done\":false}],"
                + "\"ledger\":[{\"action\":\"requested\",\"actor\":\"sigrid\",\"note\":null,\"envelopeId\":null,\"envelopeName\":null,\"at\":\"t1\"}],"
                + "\"canApprove\":false}", out);
    }

    @Test
    void governanceRequest_readsIdsOrRefsAsChannels() throws Exception {
        GovernanceRequest r = json.readValue("{\"note\":\"go\",\"force\":true,\"channel\":[\"web\",{\"id\":\"app\"}],\"done\":\"false\",\"stray\":1}",
                GovernanceRequest.class);
        assertEquals("go", r.note());
        assertTrue(r.force());
        assertFalse(r.done());
        assertEquals("web", r.channel().get(0).asText());
        assertEquals("app", r.channel().get(1).get("id").asText());
        assertEquals(" — go", r.noteSuffix());
    }

    @Test
    void configurationRequest_acceptsEveryShapeThePicksArrive_in() throws Exception {
        ProductConfigurationRequest.Query flat = json.readValue("{\"productOffering\":{\"id\":\"po-1\"}}", ProductConfigurationRequest.Query.class);
        assertEquals("po-1", flat.configuration().productOffering().id());
        ProductConfigurationRequest.Query nested = json.readValue("{\"productConfiguration\":{\"productOffering\":{\"id\":\"po-2\"}}}",
                ProductConfigurationRequest.Query.class);
        assertEquals("po-2", nested.configuration().productOffering().id());

        ProductConfigurationRequest.Check check = json.readValue("{\"checkProductConfigurationItem\":[{\"id\":1,\"productConfiguration\":"
                + "{\"productOffering\":{\"id\":\"po-1\"},\"selectedOption\":[{\"id\":\"o1\"}],\"quantity\":\"3\",\"priceOnly\":\"true\","
                + "\"configurationCharacteristic\":\"screens=5+, extraProfiles=6\"}}]}", ProductConfigurationRequest.Check.class);
        ProductConfigurationRequest.Check.Item item = check.checkProductConfigurationItem().get(0);
        assertEquals("1", item.id());
        assertEquals(3, item.configuration().quantityOr(1));
        assertTrue(item.configuration().isPriceOnly());
        assertTrue(item.configuration().configurationCharacteristic().isTextual());
        assertEquals("o1", item.configuration().selectedOption().get(0).id());
        assertEquals(1, json.readValue("{}", ProductConfigurationRequest.Check.Item.class).configuration().quantityOr(1));
    }

    @Test
    void computedItem_keepsTheHouseKeysAndDropsWhatIsEmpty() throws Exception {
        ComputedProductConfigurationItem item = new ComputedProductConfigurationItem(EntityRef.of("po-1", "Fibre", "ProductOffering"),
                true, null, null, null, null, null, null, false, new Availability(true, List.of(new Availability.Row(java.util.Map.of("colour", "black"), 2))),
                "ComputedProductConfiguration");
        assertEquals("{\"productOffering\":{\"id\":\"po-1\",\"name\":\"Fibre\",\"@referredType\":\"ProductOffering\"},\"isBundle\":true,"
                + "\"fungible\":false,\"availability\":{\"managed\":true,\"rows\":[{\"characteristics\":{\"colour\":\"black\"},\"available\":2}]},"
                + "\"@type\":\"ComputedProductConfiguration\"}", json.writeValueAsString(item));
        assertEquals("[]", json.writeValueAsString(new ArrayList<>()));
    }
}
