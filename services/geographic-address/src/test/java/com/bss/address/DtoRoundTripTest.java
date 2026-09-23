package com.bss.address;

import com.bss.address.dto.AddressValidationRequest;
import com.bss.address.dto.AddressValidationResult;
import com.bss.address.dto.GeographicAddressRequest;
import com.bss.address.dto.GeographicAddressView;
import com.bss.address.dto.GeographicSiteRequest;
import com.bss.address.dto.GeographicSiteView;
import com.bss.address.dto.RegistryConfigRequest;
import com.bss.address.dto.RegistryConfigView;
import com.bss.address.dto.RegistryMatch;
import com.bss.address.dto.RegistryTestResult;
import com.bss.address.dto.SitePlace;
import com.bss.address.dto.StandardizedAddress;
import com.bss.address.entity.GeographicAddress;
import com.bss.address.entity.RegistryConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure Jackson, no Spring context: the bytes and the key order of every
 * geographic-address wire record, pinned against what the map path wrote.
 */
class DtoRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private String write(Object o) throws Exception {
        return mapper.writeValueAsString(o);
    }

    private static Map<String, Object> submitted() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("street1", "storgatan 9 b");
        m.put("postCode", "222 33");
        m.put("city", "gÖTEBORG");
        m.put("country", "se");
        m.put("madeUpKey", "kept");
        return m;
    }

    private static GeographicAddress storedAddress() {
        GeographicAddress a = new GeographicAddress();
        a.setId("a-1");
        a.setHref("/tmf-api/geographicAddressManagement/v4/geographicAddress/a-1");
        a.setStreet1("Storgatan 9 B");
        a.setPostCode("22233");
        a.setCity("Göteborg");
        a.setCountry("SE");
        return a;
    }

    /* ---------- validation ---------- */

    @Test
    void aSuccessfulValidationEchoesTheSubmittedBlockVerbatim() throws Exception {
        StandardizedAddress std = new StandardizedAddress("storgatan 9 b", null, "22233",
                "Göteborg", null, "SE", null).withType();
        assertThat(write(AddressValidationResult.success("v-1", submitted(), std)))
                .isEqualTo("{\"id\":\"v-1\",\"@type\":\"GeographicAddressValidation\","
                        + "\"submittedGeographicAddress\":{\"street1\":\"storgatan 9 b\","
                        + "\"postCode\":\"222 33\",\"city\":\"gÖTEBORG\",\"country\":\"se\","
                        + "\"madeUpKey\":\"kept\"},\"validationResult\":\"success\","
                        + "\"standardizedGeographicAddress\":{\"street1\":\"storgatan 9 b\","
                        + "\"postCode\":\"22233\",\"city\":\"Göteborg\",\"country\":\"SE\","
                        + "\"@type\":\"GeographicAddress\"}}");
    }

    @Test
    void aFailedValidationCarriesTheReasonAndNoStandardizedBlock() throws Exception {
        assertThat(write(AddressValidationResult.failed("v-1", Map.of("country", "SE"),
                "street1 is required; postCode is required; city is required")))
                .isEqualTo("{\"id\":\"v-1\",\"@type\":\"GeographicAddressValidation\","
                        + "\"submittedGeographicAddress\":{\"country\":\"SE\"},"
                        + "\"validationResult\":\"failed\",\"validationReason\":"
                        + "\"street1 is required; postCode is required; city is required\"}");
    }

    @Test
    void theTwoRegistryShapesKeepTheirOwnKeyOrders() throws Exception {
        StandardizedAddress std = new StandardizedAddress("Karl Johans gate 1", null, "0150",
                "Oslo", null, "NO", null).withType();
        AddressValidationResult bound = AddressValidationResult.success("v-1", submitted(), std)
                .withRegistryMatch(new RegistryMatch.Bound("freg", "NO", "unavailable", null, null));
        assertThat(write(bound)).endsWith("\"registryMatch\":{\"provider\":\"freg\","
                + "\"country\":\"NO\",\"outcome\":\"unavailable\"}}");

        AddressValidationResult none = AddressValidationResult.success("v-1", submitted(), std)
                .withRegistryMatch(RegistryMatch.Unavailable.noRegistry("SE"));
        assertThat(write(none)).endsWith("\"registryMatch\":{\"country\":\"SE\","
                + "\"outcome\":\"unavailable\",\"reason\":\"no registry bound for SE\"}}");
    }

    @Test
    void aMatchCarriesTheRegistrysOwnDocumentAndTheMoveDate() throws Exception {
        Map<String, Object> registered = new LinkedHashMap<>();
        registered.put("street1", "Karl Johans gate 1");
        registered.put("postCode", "0150");
        assertThat(write(new RegistryMatch.Bound("freg", "NO", "match", registered, "2026-01-15")))
                .isEqualTo("{\"provider\":\"freg\",\"country\":\"NO\",\"outcome\":\"match\","
                        + "\"registeredAddress\":{\"street1\":\"Karl Johans gate 1\","
                        + "\"postCode\":\"0150\"},\"movedDate\":\"2026-01-15\"}");
        assertThat(write(new RegistryMatch.Bound("freg", "NO", "no_data", null, null)))
                .isEqualTo("{\"provider\":\"freg\",\"country\":\"NO\",\"outcome\":\"no_data\"}");
    }

    /* ---------- stored rows ---------- */

    @Test
    void anAddressLeavesOffTheColumnsThatAreNull() throws Exception {
        assertThat(write(GeographicAddressView.of(storedAddress())))
                .isEqualTo("{\"id\":\"a-1\",\"href\":\"/tmf-api/geographicAddressManagement/v4/"
                        + "geographicAddress/a-1\",\"street1\":\"Storgatan 9 B\","
                        + "\"postCode\":\"22233\",\"city\":\"Göteborg\",\"country\":\"SE\","
                        + "\"@type\":\"GeographicAddress\"}");
        GeographicAddress full = storedAddress();
        full.setStreet2("Apt 4");
        full.setStateOrProvince("Västra Götaland");
        assertThat(write(GeographicAddressView.of(full)))
                .contains("\"street1\":\"Storgatan 9 B\",\"street2\":\"Apt 4\",\"postCode\"")
                .contains("\"city\":\"Göteborg\",\"stateOrProvince\":\"Västra Götaland\",\"country\"");
    }

    @Test
    void aSiteEmbedsItsPlaceAndLeavesOffAnEmptyPartyList() throws Exception {
        assertThat(write(GeographicSiteView.of("s-1",
                "/tmf-api/geographicSiteManagement/v4/geographicSite/s-1", "Oslo branch", null,
                "planned", List.of(), SitePlace.of(storedAddress()),
                OffsetDateTime.parse("2026-09-23T08:00:00Z"))))
                .isEqualTo("{\"id\":\"s-1\",\"href\":\"/tmf-api/geographicSiteManagement/v4/"
                        + "geographicSite/s-1\",\"name\":\"Oslo branch\",\"status\":\"planned\","
                        + "\"place\":[{\"id\":\"a-1\",\"street1\":\"Storgatan 9 B\","
                        + "\"postCode\":\"22233\",\"city\":\"Göteborg\",\"country\":\"SE\","
                        + "\"@referredType\":\"GeographicAddress\"}],"
                        + "\"lastUpdate\":\"2026-09-23T08:00:00Z\",\"@type\":\"GeographicSite\"}");
    }

    @Test
    void aSiteWhoseAddressIsGoneAnswersWithoutAPlaceAtAll() throws Exception {
        Map<String, Object> party = new LinkedHashMap<>();
        party.put("id", "op-genalpha");
        party.put("role", "owner");
        assertThat(write(GeographicSiteView.of("s-1", "/h", "Oslo branch", "the branch",
                "active", List.of(party), null, OffsetDateTime.parse("2026-09-23T08:00:00Z"))))
                .isEqualTo("{\"id\":\"s-1\",\"href\":\"/h\",\"name\":\"Oslo branch\","
                        + "\"description\":\"the branch\",\"status\":\"active\","
                        + "\"relatedParty\":[{\"id\":\"op-genalpha\",\"role\":\"owner\"}],"
                        + "\"lastUpdate\":\"2026-09-23T08:00:00Z\",\"@type\":\"GeographicSite\"}");
    }

    /* ---------- registry bindings ---------- */

    @Test
    void aBindingPublishesTheSecretRefAndNeverTheSecret() throws Exception {
        RegistryConfig c = new RegistryConfig();
        c.setCountry("NO");
        c.setProvider("freg");
        c.setDisplayName("Folkeregisteret");
        c.setEnabled(true);
        assertThat(write(RegistryConfigView.of(c))).isEqualTo("{\"country\":\"NO\","
                + "\"provider\":\"freg\",\"displayName\":\"Folkeregisteret\",\"enabled\":true,"
                + "\"@type\":\"RegistryConfig\"}");
        c.setBaseUrl("http://mock-freg:8080");
        c.setSecretRef("FREG_API_KEY");
        assertThat(write(RegistryConfigView.of(c)))
                .contains("\"baseUrl\":\"http://mock-freg:8080\",\"secretRef\":\"FREG_API_KEY\","
                        + "\"enabled\":true");
    }

    @Test
    void aProbeCarriesTheStatusOnlyWhenItActuallyProbed() throws Exception {
        assertThat(write(RegistryTestResult.nothingToProbe("ZZ", "freg")))
                .isEqualTo("{\"country\":\"ZZ\",\"provider\":\"freg\",\"ok\":true,"
                        + "\"note\":\"no base URL configured — nothing to probe\"}");
        assertThat(write(RegistryTestResult.probed("NO", "freg", 200, "http://mock-freg:8080")))
                .isEqualTo("{\"country\":\"NO\",\"provider\":\"freg\",\"ok\":true,"
                        + "\"status\":200,\"note\":\"reachability probe of "
                        + "http://mock-freg:8080/health — never a person lookup\"}");
        assertThat(write(RegistryTestResult.probed("NO", "freg", 503, "http://x"))).contains("\"ok\":false,\"status\":503");
        assertThat(write(RegistryTestResult.unreachable("NO", "freg", "connect timed out")))
                .isEqualTo("{\"country\":\"NO\",\"provider\":\"freg\",\"ok\":false,"
                        + "\"note\":\"unreachable: connect timed out\"}");
    }

    /* ---------- request bodies ---------- */

    @Test
    void onlyAJsonFalseDisablesABinding() throws Exception {
        assertThat(mapper.readValue("{\"provider\":\"freg\"}", RegistryConfigRequest.class)
                .enabledOrDefault()).isTrue();
        assertThat(mapper.readValue("{\"enabled\":false}", RegistryConfigRequest.class)
                .enabledOrDefault()).isFalse();
        // the map path compared Boolean.FALSE against the parsed value: a string never matched
        assertThat(mapper.readValue("{\"enabled\":\"false\"}", RegistryConfigRequest.class)
                .enabledOrDefault()).isTrue();
        assertThat(mapper.readValue("{\"enabled\":0}", RegistryConfigRequest.class)
                .enabledOrDefault()).isTrue();
    }

    @Test
    void aCreateBecomesThePostalWashItAlwaysWas() throws Exception {
        GeographicAddressRequest posted = mapper.readValue(
                "{\"street1\":\"Storgatan 9\",\"postCode\":22233,\"city\":\"Göteborg\","
                        + "\"country\":\"SE\",\"geographicSubAddress\":{\"buildingName\":\"A\"}}",
                GeographicAddressRequest.class);
        assertThat(posted.postCode()).isEqualTo("22233");
        assertThat(posted.street2()).isNull();
        AddressValidationRequest wash = AddressValidationRequest.of(posted);
        assertThat(write(wash.submittedGeographicAddress()))
                .isEqualTo("{\"street1\":\"Storgatan 9\",\"postCode\":\"22233\","
                        + "\"city\":\"Göteborg\",\"country\":\"SE\"}");
        assertThat(wash.relatedParty()).isNull();
    }

    @Test
    void aSiteBodyKeepsThePlaceOpenBecauseItMayBeAnObjectOrAList() throws Exception {
        GeographicSiteRequest asList = mapper.readValue(
                "{\"name\":\"Oslo branch\",\"place\":[{\"id\":\"a-1\"}],\"unknown\":1}",
                GeographicSiteRequest.class);
        assertThat(asList.place()).isInstanceOf(List.class);
        GeographicSiteRequest asObject = mapper.readValue(
                "{\"name\":\"Oslo branch\",\"place\":{\"id\":\"a-1\"},\"status\":\"active\"}",
                GeographicSiteRequest.class);
        assertThat(asObject.place()).isInstanceOf(Map.class);
        assertThat(asObject.status()).isEqualTo("active");
        assertThat(mapper.readValue("{}", GeographicSiteRequest.class).name()).isNull();
    }

    @Test
    void aValidationBodyKeepsBothBlocksOpen() throws Exception {
        AddressValidationRequest req = mapper.readValue(
                "{\"submittedGeographicAddress\":{\"country\":\"SE\"},"
                        + "\"relatedParty\":{\"name\":\"Paula\"},\"unknown\":1}",
                AddressValidationRequest.class);
        assertThat(req.submittedGeographicAddress()).isInstanceOf(Map.class);
        assertThat(req.relatedParty()).isInstanceOf(Map.class);
        assertThat(mapper.readValue("{\"submittedGeographicAddress\":\"Storgatan 9\"}",
                AddressValidationRequest.class).submittedGeographicAddress())
                .isInstanceOf(String.class);
    }
}
