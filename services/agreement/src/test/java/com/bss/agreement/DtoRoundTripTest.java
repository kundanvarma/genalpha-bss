package com.bss.agreement;

import com.bss.agreement.dto.AgreementPatch;
import com.bss.agreement.dto.AgreementPeriod;
import com.bss.agreement.dto.AgreementRequest;
import com.bss.agreement.dto.AgreementView;
import com.bss.agreement.dto.PartnershipTypeRequest;
import com.bss.agreement.dto.PartnershipTypeView;
import com.bss.agreement.dto.RoleType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure Jackson: the bytes and the key order of every agreement wire record. */
class DtoRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private String write(Object o) throws Exception {
        return mapper.writeValueAsString(o);
    }

    private static final OffsetDateTime CLOCK = OffsetDateTime.parse("2026-09-23T08:00:00Z");

    private AgreementView minimal() {
        return AgreementView.of("a-1", "/tmf-api/agreementManagement/v4/agreement/a-1",
                "Fiber 24 months", "commercial", "inProcess", null, null,
                mapper.createArrayNode(), mapper.createArrayNode(), null, CLOCK);
    }

    @Test
    void anAgreementAnswersUnderBothNamesForTheSameTwoFacts() throws Exception {
        assertThat(write(minimal())).isEqualTo("{\"id\":\"a-1\","
                + "\"href\":\"/tmf-api/agreementManagement/v4/agreement/a-1\","
                + "\"name\":\"Fiber 24 months\",\"agreementType\":\"commercial\","
                + "\"type\":\"commercial\",\"status\":\"inProcess\",\"engagedParty\":[],"
                + "\"engagedPartyRole\":[],\"agreementItem\":[],"
                + "\"lastUpdate\":\"2026-09-23T08:00:00Z\",\"@type\":\"Agreement\"}");
    }

    @Test
    void theFullShapeKeepsTheStoredBlocksVerbatim() throws Exception {
        AgreementView v = AgreementView.of("a-1", "/h", "Fiber 24 months", "commercial", "active",
                AgreementPeriod.of(CLOCK, CLOCK.plusMonths(12)), 12,
                mapper.readTree("[{\"id\":\"p-1\",\"role\":\"customer\"}]"),
                mapper.readTree("[{\"productOffering\":{\"id\":\"off-1\"}}]"),
                mapper.readTree("{\"sla\":\"gold\",\"noticeMonths\":1}"), CLOCK);
        assertThat(write(v)).isEqualTo("{\"id\":\"a-1\",\"href\":\"/h\","
                + "\"name\":\"Fiber 24 months\",\"agreementType\":\"commercial\","
                + "\"type\":\"commercial\",\"status\":\"active\","
                + "\"agreementPeriod\":{\"startDateTime\":\"2026-09-23T08:00Z\","
                + "\"endDateTime\":\"2027-09-23T08:00Z\"},\"commitmentMonths\":12,"
                + "\"engagedParty\":[{\"id\":\"p-1\",\"role\":\"customer\"}],"
                + "\"engagedPartyRole\":[{\"id\":\"p-1\",\"role\":\"customer\"}],"
                + "\"agreementItem\":[{\"productOffering\":{\"id\":\"off-1\"}}],"
                + "\"characteristic\":{\"sla\":\"gold\",\"noticeMonths\":1},"
                + "\"lastUpdate\":\"2026-09-23T08:00:00Z\",\"@type\":\"Agreement\"}");
    }

    @Test
    void theStoredPeriodIsWrittenWithTheClocksOwnToString() throws Exception {
        // the map path wrote period ends with .toString(), which drops :00 seconds
        assertThat(write(AgreementPeriod.of(CLOCK, null)))
                .isEqualTo("{\"startDateTime\":\"2026-09-23T08:00Z\"}");
        assertThat(write(AgreementPeriod.of(null, CLOCK)))
                .isEqualTo("{\"endDateTime\":\"2026-09-23T08:00Z\"}");
        assertThat(AgreementPeriod.of(null, null)).isNull();
    }

    @Test
    void aCharacteristicMayBeAMapOrAListAndNeitherIsReShaped() throws Exception {
        assertThat(write(AgreementView.of("a-1", "/h", "n", "commercial", "active", null, null,
                mapper.createArrayNode(), mapper.createArrayNode(),
                mapper.readTree("[{\"name\":\"sla\",\"value\":\"gold\"}]"), CLOCK)))
                .contains("\"characteristic\":[{\"name\":\"sla\",\"value\":\"gold\"}],");
    }

    @Test
    void theFieldsProjectionSeesTheRecordsOwnOrderAndItsAbsentKeys() throws Exception {
        ObjectNode full = mapper.valueToTree(minimal());
        assertThat(full.has("characteristic")).isFalse();
        assertThat(full.has("commitmentMonths")).isFalse();
        ObjectNode slim = mapper.createObjectNode();
        for (String key : new String[] {"id", "name", "status", "characteristic", "nosuch"}) {
            if (full.has(key)) {
                slim.set(key, full.get(key));
            }
        }
        assertThat(write(slim))
                .isEqualTo("{\"id\":\"a-1\",\"name\":\"Fiber 24 months\",\"status\":\"inProcess\"}");
    }

    /* ---------- TMF668 ---------- */

    @Test
    void aPartnershipTypeWritesItsRolesInTheOrderItSanitisedThem() throws Exception {
        assertThat(write(PartnershipTypeView.of("t-1",
                "/tmf-api/partnershipTypeManagement/v4/partnershipType/t-1", "Wholesale access",
                null, "active",
                List.of(RoleType.of("accessProvider", "sells access"),
                        RoleType.of("accessSeeker", null)), CLOCK)))
                .isEqualTo("{\"id\":\"t-1\",\"href\":\"/tmf-api/partnershipTypeManagement/v4/"
                        + "partnershipType/t-1\",\"name\":\"Wholesale access\","
                        + "\"status\":\"active\",\"roleType\":["
                        + "{\"name\":\"accessProvider\",\"description\":\"sells access\","
                        + "\"@type\":\"PartnerRoleType\"},"
                        + "{\"name\":\"accessSeeker\",\"@type\":\"PartnerRoleType\"}],"
                        + "\"lastUpdate\":\"2026-09-23T08:00:00Z\",\"@type\":\"PartnershipType\"}");
        assertThat(write(PartnershipTypeView.of("t-1", "/h", "Inert", "a kind that permits nothing",
                "active", List.of(), CLOCK)))
                .contains("\"name\":\"Inert\",\"description\":\"a kind that permits nothing\",")
                .contains("\"roleType\":[],");
    }

    @Test
    void aStoredRoleListParsesBackIntoTheSameBytes() throws Exception {
        String stored = "[{\"name\":\"accessProvider\",\"description\":\"sells access\","
                + "\"@type\":\"PartnerRoleType\"},"
                + "{\"name\":\"accessSeeker\",\"@type\":\"PartnerRoleType\"}]";
        List<RoleType> roles = mapper.readValue(stored,
                mapper.getTypeFactory().constructCollectionType(List.class, RoleType.class));
        assertThat(write(roles)).isEqualTo(stored);
    }

    /* ---------- request bodies ---------- */

    @Test
    void theTwoNamesForTheTypeAreOneFactAndAgreementTypeWins() throws Exception {
        assertThat(mapper.readValue("{\"type\":\"commercial\"}", AgreementRequest.class)
                .resolvedType()).isEqualTo("commercial");
        assertThat(mapper.readValue("{\"agreementType\":\"partnership\",\"type\":\"commercial\"}",
                AgreementRequest.class).resolvedType()).isEqualTo("partnership");
        assertThat(mapper.readValue("{\"name\":\"x\"}", AgreementRequest.class).resolvedType())
                .isNull();
    }

    @Test
    void theRolePartyListFallsBackToTheSpecsNameOnlyWhenTheFleetsIsAbsent() throws Exception {
        assertThat(write(mapper.readValue(
                "{\"engagedPartyRole\":[{\"id\":\"p-1\"}]}", AgreementRequest.class)
                .resolvedParties())).isEqualTo("[{\"id\":\"p-1\"}]");
        assertThat(write(mapper.readValue(
                "{\"engagedParty\":[{\"id\":\"a\"}],\"engagedPartyRole\":[{\"id\":\"b\"}]}",
                AgreementRequest.class).resolvedParties())).isEqualTo("[{\"id\":\"a\"}]");
        // an explicit null engagedParty was a missing key to the map path, and still is
        assertThat(write(mapper.readValue(
                "{\"engagedParty\":null,\"engagedPartyRole\":[{\"id\":\"b\"}]}",
                AgreementRequest.class).resolvedParties())).isEqualTo("[{\"id\":\"b\"}]");
        assertThat(mapper.readValue("{}", AgreementRequest.class).resolvedParties()).isNull();
    }

    @Test
    void onlyARealJsonNumberSetsTheCommitment() throws Exception {
        assertThat(mapper.readValue("{\"commitmentMonths\":12}", AgreementRequest.class)
                .commitmentMonths().isNumber()).isTrue();
        assertThat(mapper.readValue("{\"commitmentMonths\":\"12\"}", AgreementRequest.class)
                .commitmentMonths().isNumber()).isFalse();
        assertThat(AgreementRequest.present(
                mapper.readValue("{\"commitmentMonths\":null}", AgreementRequest.class)
                        .commitmentMonths())).isFalse();
    }

    @Test
    void theSmallBodiesTakeOnlyWhatTheyDeclare() throws Exception {
        assertThat(mapper.readValue("{\"status\":\"active\",\"ownerPartyId\":\"someone-else\"}",
                AgreementPatch.class).status()).isEqualTo("active");
        assertThat(mapper.readValue("{}", AgreementPatch.class).status()).isNull();
        PartnershipTypeRequest t = mapper.readValue(
                "{\"name\":\"Wholesale\",\"roleType\":[{\"name\":\"accessProvider\"}],"
                        + "\"tenantId\":\"other\"}", PartnershipTypeRequest.class);
        assertThat(t.roleType().isArray()).isTrue();
        assertThat(t.status()).isNull();
    }
}
