package com.bss.payment;

import com.bss.payment.client.PaymentMethodClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sending money back is its own permission.
 *
 * A customer needs payment:write to pay for anything, and refund used to sit
 * behind that same authority. Ownership was checked, so nobody could reach
 * another customer's money — the hole was that reaching your own was enough:
 * a customer could refund their own captured payment at will and keep
 * whatever they had bought.
 *
 * A care agent does not get this authority either. An agent's way to put
 * money back is the governed issueCredit action, which carries a ceiling, an
 * approver above a threshold, and a receipt.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RefundPermissionTest {

    private static final String BASE = "/tmf-api/paymentManagement/v4/payment";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PaymentMethodClient paymentMethodClient;

    private static RequestPostProcessor customer(String sub) {
        return jwt().jwt(j -> j.subject(sub)).authorities(
                new SimpleGrantedAuthority("customer"),
                new SimpleGrantedAuthority("payment:read"),
                new SimpleGrantedAuthority("payment:write"));
    }

    /** A care agent: plenty of authority, and none of it this one. */
    private static RequestPostProcessor careAgent() {
        return jwt().authorities(
                new SimpleGrantedAuthority("agent"),
                new SimpleGrantedAuthority("payment:read"),
                new SimpleGrantedAuthority("payment:write"));
    }

    /** Back office, or billing resolving a dispute a human already decided. */
    private static RequestPostProcessor refunder() {
        return jwt().authorities(
                new SimpleGrantedAuthority("payment:read"),
                new SimpleGrantedAuthority("payment:write"),
                new SimpleGrantedAuthority("payment:refund"));
    }

    private String aPaymentMadeBy(RequestPostProcessor who) throws Exception {
        MvcResult made = mockMvc.perform(post(BASE).with(who)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "one-time charges",
                                 "amount": {"unit": "EUR", "value": 49.00},
                                 "paymentMethod": {"@type": "bankCard", "cardNumber": "4242424242424242",
                                                   "expiry": "12/28", "cvc": "123"}}
                                """))
                .andExpect(status().isCreated()).andReturn();
        return made.getResponse().getContentAsString().replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }

    @Test
    void aCustomerCannotRefundTheirOwnPayment() throws Exception {
        String id = aPaymentMadeBy(customer("paula"));

        // their own payment, their own token, and still no
        mockMvc.perform(post(BASE + "/" + id + "/refund").with(customer("paula"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aCareAgentCannotRefundEither() throws Exception {
        String id = aPaymentMadeBy(customer("paula"));

        mockMvc.perform(post(BASE + "/" + id + "/refund").with(careAgent())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void theRefundAuthorityGetsPastTheDoor() throws Exception {
        String id = aPaymentMadeBy(customer("paula"));

        // past authorization — whatever the payment's state then decides is
        // the business rule's business, not the door's
        mockMvc.perform(post(BASE + "/" + id + "/refund").with(refunder())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status == 401 || status == 403) {
                        throw new AssertionError("payment:refund was refused at the door: " + status);
                    }
                });
    }

    @Test
    void takingMoneyStillOnlyNeedsWrite() throws Exception {
        // the new authority must not have made paying harder
        aPaymentMadeBy(customer("paula"));
    }
}
