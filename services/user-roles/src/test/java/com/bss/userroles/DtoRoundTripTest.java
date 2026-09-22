package com.bss.userroles;

import com.bss.userroles.dto.AdvanceClockRequest;
import com.bss.userroles.dto.BaseImportReport;
import com.bss.userroles.dto.BrandPatch;
import com.bss.userroles.dto.BrandView;
import com.bss.userroles.dto.CloneReceipt;
import com.bss.userroles.dto.ClockReceipt;
import com.bss.userroles.dto.CreateUserRequest;
import com.bss.userroles.dto.EraseReceipt;
import com.bss.userroles.dto.GrantRequest;
import com.bss.userroles.dto.IdpRole;
import com.bss.userroles.dto.IdpUser;
import com.bss.userroles.dto.ImportBaseRequest;
import com.bss.userroles.dto.MutateReceipt;
import com.bss.userroles.dto.OnboardReceipt;
import com.bss.userroles.dto.OnboardRequest;
import com.bss.userroles.dto.OperatorPatch;
import com.bss.userroles.dto.PermissionView;
import com.bss.userroles.dto.ProspectSimulation;
import com.bss.userroles.dto.ProspectSimulationRequest;
import com.bss.userroles.dto.QuarterResult;
import com.bss.userroles.dto.RoleView;
import com.bss.userroles.dto.TwinBaseReceipt;
import com.bss.userroles.dto.UserView;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wire records write the bytes the maps used to write: the TMF672
 * faces, the onboarding receipts, the two shapes of a simulated quarter.
 * Pure Jackson, configured as Spring Boot configures it — no context, no
 * IdP.
 */
class DtoRoundTripTest {

    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private String write(Object o) throws Exception {
        return json.writeValueAsString(o);
    }

    @Test
    void userViews_leaveAbsentProfileFieldsOff_andShowThePasswordOnce() throws Exception {
        assertEquals("{\"id\":\"u1\",\"username\":\"anna\",\"givenName\":\"Anna\",\"@type\":\"User\"}",
                write(UserView.of(new IdpUser("u1", "anna", null, "Anna", null))));
        assertEquals("{\"id\":\"u2\",\"username\":\"a@b.c\",\"email\":\"a@b.c\",\"givenName\":\"A\",\"familyName\":\"B\","
                + "\"temporaryPassword\":\"pw\",\"@type\":\"User\"}", write(UserView.created("u2", "a@b.c", "A", "B", "pw")));
        // the login this service reads back from its own door parses into the same record
        UserView back = json.readValue("{\"id\":\"u2\",\"username\":\"a@b.c\",\"temporaryPassword\":\"pw\",\"@type\":\"User\"}",
                UserView.class);
        assertEquals("pw", back.temporaryPassword());
    }

    @Test
    void idpDocuments_parseWithVendorKeysIgnored() throws Exception {
        List<IdpRole> roles = json.readValue("[{\"id\":\"r1\",\"name\":\"agent\",\"description\":\"d\",\"composite\":false,"
                + "\"clientRole\":false,\"containerId\":\"bss\"}]", new TypeReference<List<IdpRole>>() { });
        assertEquals("agent", roles.get(0).name());
        // written back as a role mapping: id + name (+ description), nothing invented
        assertEquals("[{\"id\":\"r1\",\"name\":\"agent\",\"description\":\"d\"}]", write(roles));
        IdpUser u = json.readValue("{\"id\":\"u1\",\"username\":\"anna\",\"firstName\":\"Anna\",\"enabled\":true,"
                + "\"attributes\":{}}", IdpUser.class);
        assertEquals("Anna", u.firstName());
        assertNull(u.email());
    }

    @Test
    void rolesAndPermissions_writeTheStandardsShape() throws Exception {
        assertEquals("{\"name\":\"agent\",\"@type\":\"UserRole\"}", write(RoleView.of(new IdpRole("r1", "agent", null))));
        assertEquals("{\"name\":\"agent\",\"description\":\"d\",\"@type\":\"UserRole\"}",
                write(RoleView.of(new IdpRole("r1", "agent", "d"))));
        assertEquals("{\"id\":\"dTl-YWdlbnQ\",\"user\":{\"id\":\"u9\",\"@referredType\":\"User\"},"
                + "\"userRole\":{\"name\":\"agent\",\"@referredType\":\"UserRole\"},\"@type\":\"Permission\"}",
                write(PermissionView.of("u9", "agent")));
        GrantRequest g = json.readValue("{\"user\":{\"id\":\"u9\",\"href\":\"x\"},\"userRole\":{\"name\":\"agent\"},\"foo\":1}",
                GrantRequest.class);
        assertEquals("u9", g.user().id());
        assertNull(json.readValue("{\"userRole\":{\"name\":\"agent\"}}", GrantRequest.class).user());
        CreateUserRequest c = json.readValue("{\"email\":\"a@b.c\",\"givenName\":\"A\"}", CreateUserRequest.class);
        assertNull(c.familyName());
    }

    @Test
    void privacyReceipt_writesCountsThenTheNote() throws Exception {
        assertEquals("{\"category\":\"identity\",\"deleted\":1,\"retained\":0,\"note\":\"n\"}",
                write(new EraseReceipt("identity", 1, 0, "n")));
    }

    @Test
    void onboardingReceipts_writeInDeclarationOrder() throws Exception {
        OnboardReceipt made = new OnboardReceipt("acme", "Acme", "en", "EUR", "shop.acme.localhost", 12);
        assertEquals("{\"id\":\"acme\",\"name\":\"Acme\",\"locale\":\"en\",\"currency\":\"EUR\","
                + "\"storefrontHost\":\"shop.acme.localhost\",\"seconds\":12}", write(made));
        assertEquals("{\"id\":\"acme\",\"name\":\"Acme\",\"locale\":\"en\",\"currency\":\"EUR\","
                + "\"storefrontHost\":\"shop.acme.localhost\",\"seconds\":12,\"sourceId\":\"genalpha\",\"sandbox\":true,"
                + "\"copied\":{\"categories\":3,\"specifications\":2,\"prices\":10,\"offerings\":8,\"policyRules\":4,"
                + "\"rateCards\":1}}",
                write(new CloneReceipt(made, "genalpha", true,
                        new CloneReceipt.CopyCounts(3, 2, 10, 8, 0, 0).withRules(4, 1))));
        assertEquals("{\"id\":\"acme\",\"name\":\"Acme\",\"color\":\"#fff\",\"tagline\":null}",
                write(new BrandView("acme", "Acme", "#fff", null)));
        assertEquals("{\"id\":\"acme\",\"mutated\":false}", write(new MutateReceipt("acme", false)));
        assertEquals("{\"cloneId\":\"acme\",\"clockOffsetDays\":60}", write(new ClockReceipt("acme", 60)));
        Map<String, Integer> distribution = new LinkedHashMap<>();
        distribution.put("Mobile M", 2);
        distribution.put("Fiber", 1);
        assertEquals("{\"cloneId\":\"acme\",\"sourceId\":\"genalpha\",\"seeded\":3,\"distribution\":{\"Mobile M\":2,\"Fiber\":1},"
                + "\"privacy\":\"p\"}", write(new TwinBaseReceipt("acme", "genalpha", 3, distribution, "p")));
    }

    @Test
    void onboardingRequests_readTheirFields_andDefaultTheRest() throws Exception {
        OnboardRequest o = json.readValue("{\"id\":\"Acme\",\"currency\":\"NOK\",\"stranger\":1}", OnboardRequest.class);
        assertEquals("Acme", o.id());
        assertNull(o.name());
        assertNull(json.readValue("{\"name\":\"Nameless\"}", OnboardRequest.class).id());
        OperatorPatch p = json.readValue("{\"agentCommerce\":\"full\",\"tagline\":\"t\"}", OperatorPatch.class);
        assertEquals("full", p.agentCommerce());
        assertNull(p.name());
        BrandPatch b = json.readValue("{\"locale\":\"no\"}", BrandPatch.class);
        assertTrue(b.isEmpty());
        assertNull(OperatorPatch.brand(new BrandPatch("N", null, "T")).locale());
        assertEquals(30, json.readValue("{}", AdvanceClockRequest.class).daysOrDefault());
        assertEquals(7, json.readValue("{\"days\":\"7\"}", AdvanceClockRequest.class).daysOrDefault());
    }

    @Test
    void quarter_hasTwoHonestShapes() throws Exception {
        QuarterResult.SimulatedQuarter q = QuarterResult.SimulatedQuarter.of("acme", List.of(
                new QuarterResult.Cycle(1, 30, json.readTree("{\"id\":\"run-1\",\"billed\":3}")),
                new QuarterResult.Cycle(2, 60, json.readTree("{\"error\":\"409 busy\"}"))));
        assertEquals("{\"@type\":\"SimulatedQuarter\",\"cloneId\":\"acme\",\"cycle\":[{\"cycle\":1,\"clockOffsetDays\":30,"
                + "\"run\":{\"id\":\"run-1\",\"billed\":3}},{\"cycle\":2,\"clockOffsetDays\":60,\"run\":{\"error\":\"409 busy\"}}],"
                + "\"assumptions\":[\"dates were compressed: 3 cycles of +30 days on the sandbox clock\","
                + "\"recurring charges only — usage-dependent lines reflect seeded meters, not a lived quarter\","
                + "\"billed by the SAME engine as production; no day billed twice across cycles\"]}", write(q));
        String sim = write(ProspectSimulation.of("ps1", "Acme", 2, 0, QuarterResult.Skipped.NO_BASE));
        assertTrue(sim.startsWith("{\"@type\":\"ProspectSimulation\",\"sandboxId\":\"ps1\",\"prospect\":\"Acme\",\"shelf\":2,"
                + "\"twinBase\":0,\"quarter\":{\"skipped\":\"no base mix given\"},\"assumptions\":["));
        ProspectSimulationRequest r = json.readValue("{\"priceList\":[{\"offeringName\":\"M\",\"monthly\":\"249.0\"}],"
                + "\"baseMix\":[{\"offeringName\":\"M\",\"subscribers\":\"3\"}]}", ProspectSimulationRequest.class);
        assertEquals(new BigDecimal("249.0"), r.priceList().get(0).monthly());
        assertEquals(3, r.baseMix().get(0).subscribers());
    }

    @Test
    void baseImportReport_countsThenRowsThenExceptions() throws Exception {
        BaseImportReport rep = BaseImportReport.of("acme", 3,
                List.of(new BaseImportReport.ImportedCustomer("x1", "p1", "a@b.c", "pw", "M", "4790000001"),
                        new BaseImportReport.ImportedCustomer("x2", "p2", "d@e.f", "pw", "M", null)),
                List.of("x3"),
                List.of(new BaseImportReport.MissingOffering("x4", "Nope", "r")),
                List.of());
        String s = write(rep);
        assertTrue(s.startsWith("{\"@type\":\"BaseImport\",\"tenantId\":\"acme\",\"rows\":3,\"imported\":2,\"alreadyPresent\":1,"
                + "\"offeringMissing\":1,\"failed\":0,\"customers\":[{\"externalRef\":\"x1\",\"partyId\":\"p1\",\"email\":\"a@b.c\","
                + "\"temporaryPassword\":\"pw\",\"offeringName\":\"M\",\"msisdn\":\"4790000001\"},{\"externalRef\":\"x2\","
                + "\"partyId\":\"p2\",\"email\":\"d@e.f\",\"temporaryPassword\":\"pw\",\"offeringName\":\"M\"}],"
                + "\"exceptions\":{\"offeringMissing\":[{\"externalRef\":\"x4\",\"offeringName\":\"Nope\",\"reason\":\"r\"}],"
                + "\"alreadyPresent\":[\"x3\"],\"failed\":[]},\"readyForCutover\":false,\"assumptions\":["), s);
        ImportBaseRequest req = json.readValue("{\"rows\":[{\"externalRef\":\"x1\",\"email\":\"a@b.c\",\"msisdn\":4790000001,"
                + "\"offeringName\":\"M\"}]}", ImportBaseRequest.class);
        assertEquals("4790000001", req.rows().get(0).msisdn());
        assertNull(req.rows().get(0).givenName());
    }
}
