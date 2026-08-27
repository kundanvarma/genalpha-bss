package com.bss.billing;

import com.bss.billing.client.DownstreamClients;
import com.bss.billing.entity.CustomerBill;
import com.bss.billing.service.BillChannelService;
import com.bss.billing.service.BillDistributionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The country rails on the billing side: the consent chain resolves
 * e-invoice -> mailbox -> print with a per-send alias check; the mandate
 * file registers and cancels; the cycle run claims each bill exactly once;
 * the OCR settlement file settles through the remittance door; and a
 * protected party's letter payload carries no street — ever.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BillingRailsApiTest {

    private static final String BASE = "/tmf-api/customerBillManagement/v4";
    private static final String TENANT = "genalpha";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BillChannelService channelService;

    @Autowired
    private com.bss.billing.repository.CustomerBillRepository bills;

    @MockBean
    private DownstreamClients.OrgClient orgClient;

    @MockBean
    private DownstreamClients.PaymentClient paymentClient;

    @MockBean
    private DownstreamClients.AliasLookupClient aliasLookup;

    @MockBean
    private DownstreamClients.DirectDebitClient directDebitClient;

    private static RequestPostProcessor admin() {
        return jwt().authorities(
                new SimpleGrantedAuthority("billing:read"),
                new SimpleGrantedAuthority("billing:write"),
                new SimpleGrantedAuthority("billing:admin"));
    }

    private CustomerBill openBill(String owner, String amount) {
        CustomerBill bill = new CustomerBill();
        String id = UUID.randomUUID().toString();
        bill.setId(id);
        bill.setTenantId(TENANT);
        bill.setBillNo("BILL-TEST-" + id.substring(0, 8).toUpperCase());
        bill.setPaymentReference(String.valueOf(Math.abs(bill.getBillNo().hashCode())));
        bill.setState(CustomerBill.NEW);
        bill.setAmountDueValue(new BigDecimal(amount));
        bill.setAmountDueUnit("NOK");
        bill.setPeriodStart(LocalDate.now().withDayOfMonth(1));
        bill.setPeriodEnd(LocalDate.now().withDayOfMonth(28));
        bill.setOwnerPartyId(owner);
        bill.setBillDate(OffsetDateTime.now());
        bill.setLastUpdate(OffsetDateTime.now());
        return bills.save(bill);
    }

    @Test
    void consentChainFallsEfakturaThenMailboxThenPrint() throws Exception {
        String party = UUID.randomUUID().toString();
        mockMvc.perform(post(BASE + "/partyBillingChannel").with(admin())
                        .contentType("application/json")
                        .content("{\"partyId\":\"" + party + "\",\"channel\":\"efaktura\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("efaktura"));
        mockMvc.perform(post(BASE + "/partyBillingChannel").with(admin())
                        .contentType("application/json")
                        .content("{\"partyId\":\"" + party + "\",\"channel\":\"mailbox\"}"))
                .andExpect(status().isOk());
        given(orgClient.partyOf(party)).willReturn(Optional.of(Map.of("id", party)));

        // a LIVE alias wins the chain
        given(aliasLookup.lookup(any())).willReturn(Optional.of("alias-1"));
        assertThat(channelService.resolve(TENANT, party))
                .hasValueSatisfying(r -> {
                    assertThat(r.channel()).isEqualTo("efaktura");
                    assertThat(r.aliasRef()).isEqualTo("alias-1");
                });

        // the alias gone at the bank: the SAME consent state falls to mailbox
        given(aliasLookup.lookup(any())).willReturn(Optional.empty());
        assertThat(channelService.resolve(TENANT, party))
                .hasValueSatisfying(r -> assertThat(r.channel()).isEqualTo("mailbox"));

        // mailbox consent withdrawn: nothing resolves — print/partner is the floor
        mockMvc.perform(post(BASE + "/partyBillingChannel").with(admin())
                        .contentType("application/json")
                        .content("{\"partyId\":\"" + party + "\",\"channel\":\"mailbox\","
                                + "\"consented\":false}"))
                .andExpect(status().isOk());
        assertThat(channelService.resolve(TENANT, party)).isEmpty();
    }

    @Test
    void mandateFileRegistersAndCancels() throws Exception {
        String party = UUID.randomUUID().toString();
        mockMvc.perform(post(BASE + "/directDebit/mandateFile").with(admin())
                        .contentType("application/json")
                        .content("{\"records\":[{\"action\":\"add\",\"partyRef\":\"" + party
                                + "\",\"accountRef\":\"12345678903\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registered").value(1));
        mockMvc.perform(get(BASE + "/directDebit/mandate?partyId=" + party).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("active"));
        // the re-posted file books nothing twice
        mockMvc.perform(post(BASE + "/directDebit/mandateFile").with(admin())
                        .contentType("application/json")
                        .content("{\"records\":[{\"action\":\"add\",\"partyRef\":\"" + party
                                + "\",\"accountRef\":\"12345678903\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registered").value(0))
                .andExpect(jsonPath("$.skipped").value(1));
        mockMvc.perform(post(BASE + "/directDebit/mandateFile").with(admin())
                        .contentType("application/json")
                        .content("{\"records\":[{\"action\":\"delete\",\"partyRef\":\"" + party + "\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelled").value(1));
        mockMvc.perform(get(BASE + "/directDebit/mandate?partyId=" + party).with(admin()))
                .andExpect(jsonPath("$[0].status").value("cancelled"));
    }

    @Test
    void claimOncePerBillAndOcrSettlementSettlesThroughTheRemittanceDoor() throws Exception {
        String party = UUID.randomUUID().toString();
        CustomerBill bill = openBill(party, "129.00");
        mockMvc.perform(post(BASE + "/directDebit/mandateFile").with(admin())
                        .contentType("application/json")
                        .content("{\"records\":[{\"action\":\"add\",\"partyRef\":\"" + party
                                + "\",\"accountRef\":\"12345678903\"}]}"))
                .andExpect(status().isOk());

        mockMvc.perform(post(BASE + "/directDebit/claimRun").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claims").value(1));
        verify(directDebitClient, times(1)).sendClaim(any());
        // one claim per bill, EVER — the second cycle claims nothing new
        mockMvc.perform(post(BASE + "/directDebit/claimRun").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claims").value(0));
        verify(directDebitClient, times(1)).sendClaim(any());

        given(paymentClient.recordExternal(eq(party), any(), anyString(), anyString(),
                anyString(), anyString())).willReturn("payment-dd-1");
        given(paymentClient.validateAuthorized(eq("payment-dd-1"), eq(party), any())).willReturn("");
        String kid = bill.getPaymentReference();
        String header = "NY000010" + "0".repeat(8) + "1234567" + "0".repeat(57);
        String item = "NY" + "09" + "15" + "30" + "0000001" + "0".repeat(18)
                + String.format("%017d", 12900) + String.format("%25s", kid) + "00000";
        mockMvc.perform(post(BASE + "/directDebit/settlementFile").with(admin())
                        .contentType("text/plain").content(header + "\n" + item + "\n"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(1))
                .andExpect(jsonPath("$.source").value("directDebit"))
                .andExpect(jsonPath("$.settledClaims[0].billNo").value(bill.getBillNo()));
        verify(paymentClient).capture("payment-dd-1");
        mockMvc.perform(get(BASE + "/customerBill/" + bill.getId()).with(admin()))
                .andExpect(jsonPath("$.state").value("settled"));
    }

    @Test
    void protectedPartysLetterCarriesNoStreet() {
        CustomerBill bill = openBill("party-shielded", "99.00");
        // the party API's MASKED view of a protected party: no street keys
        Map<String, Object> masked = Map.of(
                "id", "party-shielded", "givenName", "Skjermet", "familyName", "Person",
                "contactMedium", List.of(Map.of(
                        "mediumType", "postalAddress",
                        "characteristic", Map.of("postCode", "0567", "city", "Oslo"))));
        Map<String, Object> letter = BillDistributionService.letterOf(
                masked, bill, List.of(), "123456");
        String content = String.valueOf(letter.get("content"));
        assertThat(content).doesNotContainIgnoringCase("street");
        assertThat(content).contains("0567").contains("Oslo");

        // an unprotected party's letter DOES carry the address block
        Map<String, Object> plain = Map.of(
                "id", "party-open", "givenName", "Open", "familyName", "Person",
                "contactMedium", List.of(Map.of(
                        "mediumType", "postalAddress",
                        "characteristic", Map.of("street1", "Storgata 1",
                                "postCode", "0150", "city", "Oslo"))));
        Map<String, Object> openLetter = BillDistributionService.letterOf(
                plain, bill, List.of(), "123456");
        assertThat(String.valueOf(openLetter.get("content"))).contains("Storgata 1");
    }
}
