package com.bss.billing;

import com.bss.billing.entity.BillDistribution;
import com.bss.billing.repository.BillDistributionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The credential on the distribution partner's callback
 * ({@code POST /distribution/v1/response}). The door is anonymous in the
 * filter chain and opens only to a tenant's own distribution token — and the
 * token, not the payload, decides whose delivery ledger the buyer's answer
 * can touch. Two tenants are given the same bill number here on purpose: the
 * answer must land on exactly one of them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DistributionResponseDoorTest {

    private static final String BILL_NO = "DOOR-2026-0001";

    /** A UBL ApplicationResponse: AP = accepted (UNECE 4343). */
    private static final String ACCEPTED = """
            <ApplicationResponse xmlns="urn:oasis:names:specification:ubl:schema:xsd:ApplicationResponse-2">
              <DocumentResponse>
                <Response><ResponseCode>AP</ResponseCode><Description>Booked</Description></Response>
                <DocumentReference><ID>%s</ID></DocumentReference>
              </DocumentResponse>
            </ApplicationResponse>
            """.formatted(BILL_NO);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BillDistributionRepository ledger;

    @BeforeEach
    void twoTenantsOneBillNumber() {
        ledger.deleteAll();
        ledger.save(row("door-a", "tenant-a"));
        ledger.save(row("door-b", "tenant-b"));
    }

    private static BillDistribution row(String id, String tenantId) {
        BillDistribution d = new BillDistribution();
        d.setId(id);
        d.setTenantId(tenantId);
        d.setBillId("bill-" + id);
        d.setBillNo(BILL_NO);
        d.setFormat("peppol");
        d.setChannel("einvoice");
        d.setContentType("application/xml");
        d.setPayload("<Invoice/>");
        d.setStatus(BillDistribution.SENT);
        d.setAttempts(1);
        d.setCreatedAt(OffsetDateTime.now());
        d.setLastUpdate(OffsetDateTime.now());
        return d;
    }

    @Test
    void aCallerWithNoCredentialIsRefused() throws Exception {
        mockMvc.perform(post("/distribution/v1/response")
                        .contentType(MediaType.APPLICATION_XML).content(ACCEPTED))
                .andExpect(status().isUnauthorized());
        assertThat(ledger.findById("door-a").orElseThrow().getBuyerStatus()).isNull();
        assertThat(ledger.findById("door-b").orElseThrow().getBuyerStatus()).isNull();
    }

    @Test
    void anUnknownCredentialIsRefused() throws Exception {
        mockMvc.perform(post("/distribution/v1/response")
                        .header("X-Distribution-Token", "not-a-partner")
                        .contentType(MediaType.APPLICATION_XML).content(ACCEPTED))
                .andExpect(status().isUnauthorized());
        assertThat(ledger.findById("door-a").orElseThrow().getBuyerStatus()).isNull();
    }

    @Test
    void theTenantsOwnCredentialIsAccepted() throws Exception {
        mockMvc.perform(post("/distribution/v1/response")
                        .header("X-Distribution-Token", "test-dist-token-a")
                        .contentType(MediaType.APPLICATION_XML).content(ACCEPTED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.billNo").value(BILL_NO))
                .andExpect(jsonPath("$.buyerStatus").value("accepted"))
                .andExpect(jsonPath("$.updated").value(1));
        assertThat(ledger.findById("door-a").orElseThrow().getBuyerStatus()).isEqualTo("accepted");
    }

    @Test
    void tenantAsCredentialCannotAnswerForTenantB() throws Exception {
        mockMvc.perform(post("/distribution/v1/response")
                        .header("X-Distribution-Token", "test-dist-token-a")
                        .contentType(MediaType.APPLICATION_XML).content(ACCEPTED))
                .andExpect(status().isOk());
        // the same bill number exists in tenant B; the token picked the ledger
        assertThat(ledger.findById("door-a").orElseThrow().getBuyerStatus()).isEqualTo("accepted");
        assertThat(ledger.findById("door-b").orElseThrow().getBuyerStatus()).isNull();
    }

    @Test
    void aTenantWithNoConfiguredTokenIsNotMatchedByABlankOne() throws Exception {
        // genalpha configures no distribution token in this profile; a caller
        // presenting whitespace must not fall into it
        mockMvc.perform(post("/distribution/v1/response")
                        .header("X-Distribution-Token", "   ")
                        .contentType(MediaType.APPLICATION_XML).content(ACCEPTED))
                .andExpect(status().isUnauthorized());
    }
}
