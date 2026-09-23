package com.bss.fulfilment;

import com.bss.fulfilment.dto.AppointmentRef;
import com.bss.fulfilment.dto.CarrierConfigRequest;
import com.bss.fulfilment.dto.CarrierConfigView;
import com.bss.fulfilment.dto.CarrierEvent;
import com.bss.fulfilment.dto.CarrierProbe;
import com.bss.fulfilment.dto.DeliveryOption;
import com.bss.fulfilment.dto.PartyRef;
import com.bss.fulfilment.dto.PickupPoint;
import com.bss.fulfilment.dto.ShippingOrderView;
import com.bss.fulfilment.dto.ShippingPatch;
import com.bss.fulfilment.dto.WorkOrderView;
import com.bss.fulfilment.dto.WorkPatch;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure Jackson: the bytes and the key order of the parcel and the visit. The
 * two-entry {@code Map.of}s that used to write {@code relatedParty} and
 * {@code appointment} were re-salted on every JVM start — the orders pinned here
 * were read off a snapshot of the running container, not off the source.
 */
class DtoRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final OffsetDateTime CLOCK = OffsetDateTime.parse("2026-09-23T08:00:00Z");

    private String write(Object o) throws Exception {
        return mapper.writeValueAsString(o);
    }

    @Test
    void aParcelLeavesOffEverythingTheCarrierHasNotAddedYet() throws Exception {
        assertThat(write(new ShippingOrderView("s-1", "/h", "o-1", "acknowledged",
                mapper.createArrayNode(), mapper.createArrayNode(),
                null, null, null, null, null, null, CLOCK)))
                .isEqualTo("{\"id\":\"s-1\",\"href\":\"/h\",\"productOrderId\":\"o-1\","
                        + "\"state\":\"acknowledged\",\"shippingOrderItem\":[],\"place\":[],"
                        + "\"createdAt\":\"2026-09-23T08:00:00Z\",\"@type\":\"ShippingOrder\"}");
        assertThat(write(new ShippingOrderView("s-1", "/h", "o-1", "delivered",
                mapper.readTree("[{\"id\":\"i-1\"}]"), mapper.readTree("[{\"role\":\"delivery\"}]"),
                "BRG1", "Posten/Bring", "https://tracking.bring.com/tracking/BRG1", "home",
                "Kiwi Torshov", List.of(PartyRef.customer("p-1")), CLOCK)))
                .contains("\"place\":[{\"role\":\"delivery\"}],\"trackingRef\":\"BRG1\","
                        + "\"carrier\":\"Posten/Bring\",\"trackingUrl\":"
                        + "\"https://tracking.bring.com/tracking/BRG1\",\"deliveryMethod\":\"home\","
                        + "\"pickupPoint\":\"Kiwi Torshov\",\"relatedParty\":"
                        + "[{\"role\":\"customer\",\"id\":\"p-1\"}],\"createdAt\"");
    }

    @Test
    void aVisitKeepsTheAppointmentRefsOrder() throws Exception {
        assertThat(write(AppointmentRef.of("a-1")))
                .isEqualTo("{\"@referredType\":\"Appointment\",\"id\":\"a-1\"}");
        assertThat(write(new WorkOrderView("w-1", "/h", "o-1", AppointmentRef.of("a-1"), "completed",
                mapper.readTree("{\"role\":\"installation\"}"), "installed and tested",
                List.of(PartyRef.customer("p-1")), CLOCK)))
                .isEqualTo("{\"id\":\"w-1\",\"href\":\"/h\",\"productOrderId\":\"o-1\","
                        + "\"appointment\":{\"@referredType\":\"Appointment\",\"id\":\"a-1\"},"
                        + "\"state\":\"completed\",\"place\":{\"role\":\"installation\"},"
                        + "\"note\":\"installed and tested\",\"relatedParty\":"
                        + "[{\"role\":\"customer\",\"id\":\"p-1\"}],"
                        + "\"createdAt\":\"2026-09-23T08:00:00Z\",\"@type\":\"WorkOrder\"}");
        assertThat(write(new WorkOrderView("w-1", "/h", "o-1", null, "planned",
                mapper.createArrayNode(), null, null, CLOCK)))
                .isEqualTo("{\"id\":\"w-1\",\"href\":\"/h\",\"productOrderId\":\"o-1\","
                        + "\"state\":\"planned\",\"place\":[],"
                        + "\"createdAt\":\"2026-09-23T08:00:00Z\",\"@type\":\"WorkOrder\"}");
    }

    /** isDefault must stay isDefault: a getter-shaped accessor also offers "default". */
    @Test
    void theCarrierMenuKeepsItsKeysAndNeverTheApiKey() throws Exception {
        assertThat(write(new CarrierConfigView("bring", "Posten/Bring", "http://mock-bring:8080",
                "BRING_API_KEY", "[\"home\",\"pickupPoint\"]", null, true, true)))
                .isEqualTo("{\"carrier\":\"bring\",\"displayName\":\"Posten/Bring\","
                        + "\"baseUrl\":\"http://mock-bring:8080\",\"secretRef\":\"BRING_API_KEY\","
                        + "\"methods\":\"[\\\"home\\\",\\\"pickupPoint\\\"]\",\"isDefault\":true,"
                        + "\"enabled\":true,\"@type\":\"CarrierConfig\"}");
        assertThat(write(new CarrierConfigView("http", "ENet", null, null, null, "4", false, false)))
                .isEqualTo("{\"carrier\":\"http\",\"displayName\":\"ENet\",\"postcodePrefix\":\"4\","
                        + "\"isDefault\":false,\"enabled\":false,\"@type\":\"CarrierConfig\"}");
    }

    @Test
    void theProbeCarriesAStatusOnlyWhenItReachedSomething() throws Exception {
        assertThat(write(CarrierProbe.nothingToProbe("bring")))
                .isEqualTo("{\"carrier\":\"bring\",\"ok\":true,"
                        + "\"note\":\"no base URL configured — nothing to probe\"}");
        assertThat(write(CarrierProbe.unreachable("helthjem", "null")))
                .isEqualTo("{\"carrier\":\"helthjem\",\"ok\":false,\"note\":\"unreachable: null\"}");
        assertThat(write(CarrierProbe.reached("bring", 200, "http://mock-bring:8080")))
                .isEqualTo("{\"carrier\":\"bring\",\"ok\":true,\"status\":200,"
                        + "\"note\":\"reachability probe of http://mock-bring:8080/health — not a booking\"}");
        assertThat(write(CarrierProbe.reached("bring", 503, "http://x"))).contains("\"ok\":false,\"status\":503");
    }

    @Test
    void theDeliveryMenuWritesANullCarrierOnTheBuiltInFallback() throws Exception {
        assertThat(write(DeliveryOption.HOME))
                .isEqualTo("{\"method\":\"home\",\"carrier\":null,\"carrierName\":\"Helthjem\"}");
        assertThat(write(new DeliveryOption("pickupPoint", "bring", "Posten/Bring", null, null)
                .withPoints(List.of()).withEta("1–3 days")))
                .isEqualTo("{\"method\":\"pickupPoint\",\"carrier\":\"bring\","
                        + "\"carrierName\":\"Posten/Bring\",\"points\":[],\"eta\":\"1–3 days\"}");
        assertThat(write(new PickupPoint(mapper.readTree("\"pp-1\""), mapper.readTree("\"Kiwi\""),
                mapper.readTree("{\"street\":\"Storgata 1\"}"), null)))
                .isEqualTo("{\"id\":\"pp-1\",\"name\":\"Kiwi\","
                        + "\"address\":{\"street\":\"Storgata 1\"},\"openingHours\":null}");
    }

    @Test
    void aStatePatchMintsTheSameLiteralTheMapDid() throws Exception {
        assertThat(mapper.readValue("{}", ShippingPatch.class).stateValue()).isEqualTo("null");
        assertThat(mapper.readValue("{\"state\":null}", ShippingPatch.class).stateValue()).isEqualTo("null");
        assertThat(mapper.readValue("{\"state\":7}", ShippingPatch.class).stateValue()).isEqualTo("7");
        assertThat(mapper.readValue("{\"state\":\"shipped\",\"trackingRef\":\"BRG1\"}",
                ShippingPatch.class).trackingRefValue()).isEqualTo("BRG1");
        assertThat(mapper.readValue("{\"trackingRef\":null}", ShippingPatch.class)
                .trackingRefValue()).isNull();
        assertThat(mapper.readValue("{\"state\":\"completed\",\"note\":\"done\",\"tenantId\":\"x\"}",
                WorkPatch.class).noteValue()).isEqualTo("done");
        assertThat(WorkPatch.EMPTY.stateValue()).isEqualTo("null");
    }

    @Test
    void aCarrierCallbackFallsBackTheWayTheMapDid() throws Exception {
        CarrierEvent e = mapper.readValue("{\"shippingOrderId\":\"s-1\",\"status\":\"DELIVERED\"}",
                CarrierEvent.class);
        assertThat(e.tenant("genalpha")).isEqualTo("genalpha");
        assertThat(e.parcelId()).isEqualTo("s-1");
        assertThat(mapper.readValue("{\"tenantId\":null}", CarrierEvent.class).tenant("genalpha"))
                .isEqualTo("genalpha");
        assertThat(mapper.readValue("{\"tenantId\":\"enet\"}", CarrierEvent.class).tenant("genalpha"))
                .isEqualTo("enet");
        assertThat(CarrierEvent.EMPTY.parcelId()).isEqualTo("null");
        assertThat(mapper.readValue("{\"status\":7}", CarrierEvent.class).statusValue()).isEqualTo("7");
    }

    /** Boolean.FALSE.equals and Boolean.TRUE.equals are not a Boolean field. */
    @Test
    void onlyARealJsonBooleanMovesTheCarrierFlags() throws Exception {
        assertThat(mapper.readValue("{}", CarrierConfigRequest.class).stayEnabled()).isTrue();
        assertThat(mapper.readValue("{\"enabled\":false}", CarrierConfigRequest.class)
                .stayEnabled()).isFalse();
        assertThat(mapper.readValue("{\"enabled\":\"false\"}", CarrierConfigRequest.class)
                .stayEnabled()).isTrue();     // the string never disabled a carrier
        assertThat(mapper.readValue("{\"enabled\":0}", CarrierConfigRequest.class).stayEnabled()).isTrue();
        assertThat(mapper.readValue("{\"isDefault\":true}", CarrierConfigRequest.class)
                .makeDefault()).isTrue();
        assertThat(mapper.readValue("{\"isDefault\":\"true\"}", CarrierConfigRequest.class)
                .makeDefault()).isFalse();    // the string never made one the default
        // getOrDefault: a key present with a JSON null wins the null, an absent key the fallback
        assertThat(mapper.readValue("{\"displayName\":null}", CarrierConfigRequest.class)
                .displayNameOr("bring")).isNull();
        assertThat(mapper.readValue("{}", CarrierConfigRequest.class).displayNameOr("bring"))
                .isEqualTo("bring");
        assertThat(CarrierConfigRequest.EMPTY.carrierKey()).isNull();
    }
}
