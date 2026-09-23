package com.bss.som;

import com.bss.som.entity.WholesaleAccessOrder;
import com.bss.som.repository.ProviderAccessOrderRepository;
import com.bss.som.repository.WholesaleAccessOrderRepository;
import com.bss.som.security.WholesaleDoorAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two wholesale doors, which used to be reachable with no caller identity
 * at all.
 *
 * <p>The <b>order door</b> (MEF Sonata) took its tenant from an unauthenticated
 * {@code X-Tenant-Id} header and the party to wholesale-bill from the body, so
 * anything on the private network could sell access in any operator's name. The
 * <b>activation callback</b> took nothing but an order UUID and, on a bare POST
 * with no body, completed the retail order and booked wholesale COGS.
 *
 * <p>Both now demand the operator's own wholesale secret — one as a signature
 * over the body, one as a token in the callback URL we handed the owner.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WholesaleDoorTest {

    private static final String SONATA = "/mefApi/serviceOrdering/v1/serviceOrder";
    private static final String SECRET_A = "test-wholesale-secret-a";
    private static final String SECRET_B = "test-wholesale-secret-b";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WholesaleDoorAuth auth;

    @Autowired
    private WholesaleAccessOrderRepository accessOrders;

    @Autowired
    private ProviderAccessOrderRepository providerOrders;

    private static String order(String buyerId) {
        return """
                {"externalId": "ext-%s", "callbackUrl": "http://seeker.test/cb", "buyerId": "%s",
                 "serviceOrderItem": [{"action": "add", "service": {"serviceCharacteristic": [
                   {"name": "accessLayer", "value": "L3-BITSTREAM"},
                   {"name": "bandwidthMbps", "value": 1000},
                   {"name": "postCode", "value": "5020"}]}}]}""".formatted(buyerId, buyerId);
    }

    private static String sign(String secret, String body) {
        return signAt(secret, body, System.currentTimeMillis());
    }

    private static String signAt(String secret, String body, long t) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "t=" + t + ",v1=" + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal((t + "." + body).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /* ------------------------------------------------- the order door */

    @Test
    void anUnsignedSonataOrderIsRefused() throws Exception {
        long before = providerOrders.count();
        mockMvc.perform(post(SONATA).header("X-Tenant-Id", "tenant-a")
                        .contentType(MediaType.APPLICATION_JSON).content(order("unsigned")))
                .andExpect(status().isUnauthorized());
        assertThat(providerOrders.count()).isEqualTo(before);
    }

    @Test
    void aCorrectlySignedSonataOrderIsAccepted() throws Exception {
        String body = order("signed-seeker");
        mockMvc.perform(post(SONATA).header("X-Tenant-Id", "tenant-a")
                        .header(WholesaleDoorAuth.SIGNATURE_HEADER, sign(SECRET_A, body))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        assertThat(providerOrders.findAll().stream()
                .anyMatch(o -> "signed-seeker".equals(o.getRetailerPartyId()))).isTrue();
    }

    @Test
    void tenantAsSecretCannotSellInTenantBsName() throws Exception {
        String body = order("cross-tenant");
        long before = providerOrders.count();
        mockMvc.perform(post(SONATA).header("X-Tenant-Id", "tenant-b")
                        .header(WholesaleDoorAuth.SIGNATURE_HEADER, sign(SECRET_A, body))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        assertThat(providerOrders.count()).isEqualTo(before);
        // ...and tenant B's own secret does open tenant B's door
        mockMvc.perform(post(SONATA).header("X-Tenant-Id", "tenant-b")
                        .header(WholesaleDoorAuth.SIGNATURE_HEADER, sign(SECRET_B, body))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    @Test
    void anOperatorThatSellsNoWholesaleAccessHasNoOrderDoor() throws Exception {
        String body = order("no-secret");
        // genalpha is the default tenant and configures no wholesale secret
        mockMvc.perform(post(SONATA)
                        .header(WholesaleDoorAuth.SIGNATURE_HEADER, sign(SECRET_A, body))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aStaleOrTamperedSignatureIsRefused() throws Exception {
        String body = order("stale");
        mockMvc.perform(post(SONATA).header("X-Tenant-Id", "tenant-a")
                        .header(WholesaleDoorAuth.SIGNATURE_HEADER,
                                signAt(SECRET_A, body, System.currentTimeMillis() - 3_600_000L))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        // signed over one body, sent with another (a different buyerId to bill)
        mockMvc.perform(post(SONATA).header("X-Tenant-Id", "tenant-a")
                        .header(WholesaleDoorAuth.SIGNATURE_HEADER, sign(SECRET_A, body))
                        .contentType(MediaType.APPLICATION_JSON).content(order("somebody-else")))
                .andExpect(status().isUnauthorized());
    }

    /* ------------------------------------------ the activation callback */

    @Test
    void theActivationCallbackNeedsItsOrdersOwnToken() throws Exception {
        String id = "wa-door-" + System.nanoTime();
        accessOrders.save(accessOrder(id, "tenant-a"));
        String url = "/tmf-api/serviceOrdering/v4/wholesaleAccessOrder/" + id + "/notification/";

        // the door that used to exist — a bare POST with no body — is gone
        mockMvc.perform(post("/tmf-api/serviceOrdering/v4/wholesaleAccessOrder/" + id + "/notification"))
                .andExpect(status().is4xxClientError());
        // a guessed token is refused
        mockMvc.perform(post(url + "not-the-token"))
                .andExpect(status().isUnauthorized());
        // tenant B's token for the same order id is refused
        mockMvc.perform(post(url + auth.callbackToken("tenant-b", id)))
                .andExpect(status().isUnauthorized());
        assertThat(accessOrders.findById(id).orElseThrow().getState())
                .isEqualTo(WholesaleAccessOrder.IN_PROGRESS);

        // the token we actually handed the owner opens it
        mockMvc.perform(post(url + auth.callbackToken("tenant-a", id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sonataOrderId\": \"SON-DOOR-1\"}"))
                .andExpect(status().isOk());
        assertThat(accessOrders.findById(id).orElseThrow().getState()).isEqualTo(WholesaleAccessOrder.ACTIVE);
    }

    @Test
    void anOrderNobodyPlacedIsRefusedTheSameWayAsABadToken() throws Exception {
        mockMvc.perform(post("/tmf-api/serviceOrdering/v4/wholesaleAccessOrder/no-such-order/notification/x"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aCallbackTokenIsBoundToItsOwnOrder() {
        String a = auth.callbackToken("tenant-a", "order-1");
        assertThat(a).isNotBlank()
                .isNotEqualTo(auth.callbackToken("tenant-a", "order-2"))
                .isNotEqualTo(auth.callbackToken("tenant-b", "order-1"));
    }

    private static WholesaleAccessOrder accessOrder(String id, String tenantId) {
        WholesaleAccessOrder w = new WholesaleAccessOrder();
        w.setId(id);
        w.setTenantId(tenantId);
        w.setProductOrderId("po-" + id);
        w.setAccessOwner("NORDACCESS");
        w.setAccessLayer("L3-BITSTREAM");
        w.setBandwidthMbps(1000);
        w.setPostCode("5020");
        w.setState(WholesaleAccessOrder.IN_PROGRESS);
        w.setCreatedAt(OffsetDateTime.now());
        w.setLastUpdate(OffsetDateTime.now());
        return w;
    }

}
