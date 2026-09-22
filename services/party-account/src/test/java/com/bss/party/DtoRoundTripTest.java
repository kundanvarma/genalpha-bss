package com.bss.party;

import com.bss.party.dto.DirectoryExportReceipt;
import com.bss.party.dto.DirectoryExportRunDetail;
import com.bss.party.dto.DirectoryExportRunView;
import com.bss.party.dto.DirectoryListing;
import com.bss.party.dto.DirectorySettingRequest;
import com.bss.party.dto.DirectorySettingView;
import com.bss.party.dto.EntityRef;
import com.bss.party.dto.HouseholdPayerView;
import com.bss.party.dto.HouseholdRequests;
import com.bss.party.dto.HouseholdView;
import com.bss.party.dto.IndividualDto;
import com.bss.party.dto.Money;
import com.bss.party.dto.OrganizationDto;
import com.bss.party.dto.PartyRoleRequest;
import com.bss.party.dto.PartyRoleView;
import com.bss.party.dto.RegistrySyncReceipt;
import com.bss.party.entity.DirectoryExportRun;
import com.bss.party.entity.DirectorySetting;
import com.bss.party.privacy.CategoryReceipt;
import com.bss.party.privacy.EraseRequest;
import com.bss.party.privacy.ErasureAuditRow;
import com.bss.party.privacy.ErasureReport;
import com.bss.party.privacy.PrivacyPassport;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wire records write the bytes the maps used to write: keys in the same
 * order, absent keys absent, nulls where a block is written whole, money at
 * the scale it was stored, the callers' open blocks (roleType, a contact
 * medium, a sibling's privacy shelf) passed through as written. Pure
 * Jackson, configured as Spring Boot configures it — no context, no database.
 */
class DtoRoundTripTest {

    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final OffsetDateTime T = OffsetDateTime.parse("2026-09-22T10:00:00Z");

    private String write(Object o) throws Exception {
        return json.writeValueAsString(o);
    }

    /* ------------------------------------------------------------ TMF632 refs */

    @Test
    void individualDto_writesTypedRefs_andReadsAnyRefShape() throws Exception {
        IndividualDto dto = new IndividualDto();
        dto.setId("p1");
        dto.setFamilyName("Family");
        dto.setOrganization(EntityRef.organization("o1"));
        dto.setHouseholdPayer(new HouseholdPayerView("payer", "active", null, new BigDecimal("6.0")));
        assertEquals("{\"id\":\"p1\",\"familyName\":\"Family\",\"organization\":{\"id\":\"o1\",\"@referredType\":\"Organization\"},"
                + "\"householdPayer\":{\"id\":\"payer\",\"status\":\"active\",\"role\":null,\"topupAllowance\":6.0},"
                + "\"@type\":\"Individual\"}", write(dto));
        IndividualDto in = json.readValue("{\"familyName\":\"F\",\"organization\":{\"id\":\"o9\",\"href\":\"/x\",\"name\":\"Acme\"}}",
                IndividualDto.class);
        assertEquals("o9", in.getOrganization().id());
        assertNull(in.getOrganization().referredType());
    }

    @Test
    void organizationDto_moneyKeepsTheStoredScale() throws Exception {
        OrganizationDto dto = new OrganizationDto();
        dto.setId("o1");
        dto.setName("Acme");
        dto.setParentOrganization(EntityRef.organization("o0"));
        dto.setDeviceAllowance(new Money(new BigDecimal("50.00"), "EUR"));
        assertEquals("{\"id\":\"o1\",\"name\":\"Acme\",\"parentOrganization\":{\"id\":\"o0\",\"@referredType\":\"Organization\"},"
                + "\"deviceAllowance\":{\"value\":50.00,\"unit\":\"EUR\"},\"@type\":\"Organization\"}", write(dto));
        OrganizationDto patch = json.readValue("{\"deviceAllowance\":{\"value\":\"25.5\"}}", OrganizationDto.class);
        assertEquals(new BigDecimal("25.5"), patch.getDeviceAllowance().value());
        assertNull(patch.getDeviceAllowance().unit());
    }

    /* -------------------------------------------------------------- household */

    @Test
    void householdView_writesThePayerBlockWhole_andTheFamilyOnlyForAdmins() throws Exception {
        HouseholdView.Dependent kid = new HouseholdView.Dependent("k1", "Sonny", null, "active", "child", null);
        HouseholdView member = new HouseholdView(
                new HouseholdView.PayerBlock(new HouseholdView.Payer("payer", "pending", "Paula Payer"), null),
                List.of(), null);
        assertEquals("{\"payer\":{\"id\":\"payer\",\"status\":\"pending\",\"name\":\"Paula Payer\"},\"myRole\":null,\"dependents\":[]}",
                write(member));
        HouseholdView owner = new HouseholdView(null,
                List.of(new HouseholdView.Dependent("w1", "Wilma", "Family", "active", "admin", new BigDecimal("6.0"))), null);
        assertEquals("{\"dependents\":[{\"id\":\"w1\",\"givenName\":\"Wilma\",\"familyName\":\"Family\",\"status\":\"active\","
                + "\"role\":\"admin\",\"topupAllowance\":6.0}]}", write(owner));
        HouseholdView admin = new HouseholdView(
                new HouseholdView.PayerBlock(new HouseholdView.Payer("payer", "active", null), "admin"),
                List.of(), List.of(kid));
        assertEquals("{\"payer\":{\"id\":\"payer\",\"status\":\"active\"},\"myRole\":\"admin\",\"dependents\":[],"
                + "\"family\":[{\"id\":\"k1\",\"givenName\":\"Sonny\",\"familyName\":null,\"status\":\"active\",\"role\":\"child\"}]}",
                write(admin));
    }

    @Test
    void householdRequests_keepTheOldDefaults() throws Exception {
        assertNull(json.readValue("{\"preference\":\"default\"}", HouseholdRequests.BillDeliveryRequest.class).preferenceOrNull());
        assertEquals("paper", json.readValue("{\"preference\":\"paper\"}", HouseholdRequests.BillDeliveryRequest.class).preferenceOrNull());
        assertEquals(3, json.readValue("{\"anchorDay\":\"3\"}", HouseholdRequests.BillingCycleRequest.class).anchorDay());
        assertEquals(new BigDecimal("6.0"), json.readValue("{\"monthlyValue\":6.0}", HouseholdRequests.AllowanceRequest.class).monthlyValue());
        assertNull(json.readValue("{}", HouseholdRequests.RoleRequest.class).role());
    }

    /* -------------------------------------------------------------- directory */

    @Test
    void directorySetting_leavesOffWhatTheMapLeftOff() throws Exception {
        DirectorySetting s = new DirectorySetting();
        s.setId("d1");
        s.setPartyId("p1");
        s.setExposure("reserved");
        assertEquals("{\"id\":\"d1\",\"partyId\":\"p1\",\"exposure\":\"reserved\",\"secretNumber\":false}",
                write(DirectorySettingView.of(s)));
        s.setServiceRef("svc");
        s.setSecretNumber(true);
        s.setUpdatedAt(T);
        assertEquals("{\"id\":\"d1\",\"partyId\":\"p1\",\"serviceRef\":\"svc\",\"exposure\":\"reserved\",\"secretNumber\":true,"
                + "\"updatedAt\":\"2026-09-22T10:00Z\"}", write(DirectorySettingView.of(s)));
        DirectorySettingRequest r = json.readValue("{\"secretNumber\":\"true\",\"exposure\":\"full\"}", DirectorySettingRequest.class);
        assertEquals(Boolean.TRUE, r.secretNumber());
        assertNull(r.serviceRef());
    }

    @Test
    void directoryExport_receiptIsTheRunUnwrappedFirst_thenTheRows() throws Exception {
        DirectoryExportRun run = new DirectoryExportRun();
        run.setId("r1");
        run.setRanAt(T);
        run.setRowCount(1);
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("street1", "Storgata 1");
        address.put("city", "Oslo");
        DirectoryListing full = new DirectoryListing("p1", null, "Liv Listet", "full", "+4790000000", address);
        DirectoryListing partial = new DirectoryListing("p2", "svc", "Per Partial", "partial", null, null);
        assertEquals("{\"id\":\"r1\",\"ranAt\":\"2026-09-22T10:00Z\",\"rowCount\":1,\"deltaSince\":\"2026-09-22T10:00Z\","
                + "\"rows\":[{\"partyId\":\"p1\",\"name\":\"Liv Listet\",\"exposure\":\"full\",\"phoneNumber\":\"+4790000000\","
                + "\"address\":{\"street1\":\"Storgata 1\",\"city\":\"Oslo\"}},"
                + "{\"partyId\":\"p2\",\"serviceRef\":\"svc\",\"name\":\"Per Partial\",\"exposure\":\"partial\"}]}",
                write(new DirectoryExportReceipt(DirectoryExportRunView.of(run, T), List.of(full, partial))));
        // the first run has no deltaSince; a run read back carries its stored rows verbatim
        assertEquals("{\"id\":\"r1\",\"ranAt\":\"2026-09-22T10:00Z\",\"rowCount\":1,\"rows\":[{\"partyId\":\"p2\",\"x\":1}]}",
                write(new DirectoryExportRunDetail(DirectoryExportRunView.of(run, null),
                        List.of(json.readTree("{\"partyId\":\"p2\",\"x\":1}")))));
        // a stored row written by the map parses back into the record unchanged
        assertEquals(partial, json.readValue(write(partial), DirectoryListing.class));
    }

    /* ---------------------------------------------------------------- TMF669 */

    @Test
    void partyRoleView_andRequest_keepRoleTypeOpen() throws Exception {
        PartyRoleView v = new PartyRoleView("r1", "/tmf-api/partyRoleManagement/v4/partyRole/r1", "partner", "active",
                json.readTree("{\"name\":\"typed\"}"), EntityRef.individual("p1"), "PartyRole");
        assertEquals("{\"id\":\"r1\",\"href\":\"/tmf-api/partyRoleManagement/v4/partyRole/r1\",\"name\":\"partner\","
                + "\"status\":\"active\",\"roleType\":{\"name\":\"typed\"},\"engagedParty\":{\"id\":\"p1\",\"@referredType\":\"Individual\"},"
                + "\"@type\":\"PartyRole\"}", write(v));
        PartyRoleView standalone = new PartyRoleView("r2", "h", "standalone", "active", json.createObjectNode(), null, "PartyRole");
        assertEquals("{\"id\":\"r2\",\"href\":\"h\",\"name\":\"standalone\",\"status\":\"active\",\"roleType\":{},\"@type\":\"PartyRole\"}",
                write(standalone));
        PartyRoleRequest create = json.readValue("{\"name\":\"partner\",\"engagedParty\":{\"id\":\"org-77\",\"name\":\"Org\"},"
                + "\"roleType\":\"plain\",\"characteristic\":[]}", PartyRoleRequest.class);
        assertEquals("org-77", create.engagedPartyId());
        assertTrue(create.hasRoleType());
        assertEquals("plain", create.roleType().asText());
        PartyRoleRequest clear = json.readValue("{\"roleType\":null}", PartyRoleRequest.class);
        assertTrue(clear.roleType().isNull(), "explicit null clears");
        assertFalse(clear.hasRoleType());
        assertNull(json.readValue("{\"status\":\"suspended\"}", PartyRoleRequest.class).roleType(), "absent leaves alone");
    }

    /* -------------------------------------------------------------- registry */

    @Test
    void registrySyncReceipt() throws Exception {
        assertEquals("{\"processed\":2,\"lastSeq\":10}", write(new RegistrySyncReceipt(2, 10)));
    }

    /* --------------------------------------------------------------- privacy */

    @Test
    void passport_profileShelf_andTheUnavailableShelf() throws Exception {
        Map<String, String> held = new LinkedHashMap<>();
        held.put("bills", "bookkeeping law");
        PrivacyPassport p = new PrivacyPassport("p1", "2026-09-22T10:00:00Z",
                new PrivacyPassport.ProfileView("p1", "Paula", "Family", null, json.readTree("[{\"mediumType\":\"email\"}]")),
                List.of(json.readTree("{\"category\":\"carts\",\"count\":1}"),
                        json.valueToTree(CategoryReceipt.exportUnavailable("tickets"))),
                held);
        assertEquals("{\"partyId\":\"p1\",\"exportedAt\":\"2026-09-22T10:00:00Z\",\"profile\":{\"id\":\"p1\",\"givenName\":\"Paula\","
                + "\"familyName\":\"Family\",\"birthDate\":null,\"contactMedium\":[{\"mediumType\":\"email\"}]},"
                + "\"categories\":[{\"category\":\"carts\",\"count\":1},{\"category\":\"tickets\",\"error\":\"unavailable — retry the export\"}],"
                + "\"alsoHeldUnderLegalBasis\":{\"bills\":\"bookkeeping law\"}}", write(p));
        PrivacyPassport none = new PrivacyPassport("p9", "t", PrivacyPassport.NoProfile.NONE, List.of(), held);
        assertTrue(write(none).contains("\"profile\":{\"note\":\"no profile row\"}"));
        assertEquals("{\"id\":\"p1\",\"givenName\":\"Paula\",\"familyName\":\"Family\",\"birthDate\":\"1980-01-01\",\"contactMedium\":[]}",
                write(new PrivacyPassport.ProfileView("p1", "Paula", "Family", LocalDate.of(1980, 1, 1), json.createArrayNode())));
    }

    @Test
    void erasureReport_storedWithoutTheAuditId_answeredWithIt() throws Exception {
        ErasureReport stored = new ErasureReport("p1", "completed", "dpo", "2026-09-22T10:00:00Z",
                List.of(json.valueToTree(CategoryReceipt.eraseUnavailable("carts")),
                        json.valueToTree(CategoryReceipt.profileAnonymized()),
                        json.valueToTree(CategoryReceipt.retained("bills", "bookkeeping law"))), null);
        assertEquals("{\"partyId\":\"p1\",\"status\":\"completed\",\"executedBy\":\"dpo\",\"executedAt\":\"2026-09-22T10:00:00Z\","
                + "\"categories\":[{\"category\":\"carts\",\"deleted\":0,\"error\":\"unavailable — this category is NOT erased; re-run\"},"
                + "{\"category\":\"profile\",\"deleted\":0,\"retained\":1,\"note\":\"anonymized in place — id kept for referential integrity\"},"
                + "{\"category\":\"bills\",\"deleted\":0,\"retained\":-1,\"reason\":\"bookkeeping law\"}]}", write(stored));
        assertTrue(write(stored.withAudit("a1")).endsWith(",\"auditRecordId\":\"a1\"}"));
        assertEquals("{\"id\":\"a1\",\"partyId\":\"p1\",\"executedBy\":\"dpo\",\"executedAt\":\"2026-09-22T10:00:00Z\"}",
                write(new ErasureAuditRow("a1", "p1", "dpo", "2026-09-22T10:00:00Z")));
        assertEquals("{\"partyId\":\"p1\"}", write(new EraseRequest("p1")));
    }
}
