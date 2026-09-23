package com.bss.payment;

import com.bss.payment.dto.PaymentDto;
import com.bss.payment.entity.PspConfig;
import com.bss.payment.service.PaymentService;
import com.bss.payment.service.PaymentWebhookService;
import com.bss.payment.service.PspConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * A PSP webhook confirms a payment, so the signature on it is the only thing
 * standing between a captured request and a confirmation replayed at will.
 * The timestamp was always signed — it could not be edited without the secret —
 * but nothing ever compared it to the clock, so a valid signature stayed valid
 * forever. These tests pin the window shut in both directions.
 *
 * The shared secret is read from the environment by name, so the tests point
 * the config at a variable that is always present and sign with its value;
 * no secret is invented, written down, or printed.
 */
class PaymentWebhookFreshnessTest {

    private static final String PROVIDER = "stripe";
    private static final String TENANT = "genalpha";
    private static final String SECRET_REF = "HOME";
    private static final String BODY = "{\"sessionId\":\"sess_replayed\"}";
    private static final long TOLERANCE_MS = 300_000;

    private PaymentService payments;
    private PaymentWebhookService webhooks;
    private String secret;

    @BeforeEach
    void setUp() {
        secret = System.getenv(SECRET_REF);
        assertThat(secret).as("the test signs with an environment value that must exist").isNotBlank();

        PspConfig cfg = new PspConfig();
        cfg.setWebhookSecretRef(SECRET_REF);
        PspConfigService configs = mock(PspConfigService.class);
        given(configs.forTenantAndProvider(TENANT, PROVIDER)).willReturn(Optional.of(cfg));

        payments = mock(PaymentService.class);
        PaymentDto confirmed = new PaymentDto();
        given(payments.confirmSession(anyString(), anyString(), anyString())).willReturn(confirmed);

        webhooks = new PaymentWebhookService(configs, payments, TOLERANCE_MS);
    }

    @Test
    void acceptsAWebhookSignedNow() {
        webhooks.handle(PROVIDER, TENANT, BODY.getBytes(StandardCharsets.UTF_8),
                signedAt(System.currentTimeMillis()));

        verify(payments).confirmSession(TENANT, PROVIDER, "sess_replayed");
    }

    @Test
    void refusesAWebhookReplayedAfterTheWindow() {
        // Exactly the request the PSP sent, signature and all — just kept.
        String captured = signedAt(System.currentTimeMillis() - TOLERANCE_MS - 1_000);

        assertThatThrownBy(() -> webhooks.handle(PROVIDER, TENANT,
                BODY.getBytes(StandardCharsets.UTF_8), captured))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(payments, never()).confirmSession(any(), any(), any());
    }

    @Test
    void refusesAWebhookPostDatedBeyondTheWindow() {
        // Post-dating is how a replayer would try to buy the capture more life.
        String future = signedAt(System.currentTimeMillis() + TOLERANCE_MS + 1_000);

        assertThatThrownBy(() -> webhooks.handle(PROVIDER, TENANT,
                BODY.getBytes(StandardCharsets.UTF_8), future))
                .isInstanceOf(ResponseStatusException.class);

        verify(payments, never()).confirmSession(any(), any(), any());
    }

    @Test
    void refusesAFreshTimestampCarryingAnOldSignature() {
        // The timestamp is inside the signed material, so moving it forward to
        // pass the freshness check invalidates the signature it was sent with.
        String old = signedAt(System.currentTimeMillis() - TOLERANCE_MS - 1_000);
        String signature = old.substring(old.indexOf(",v1="));
        String forged = "t=" + System.currentTimeMillis() + signature;

        assertThatThrownBy(() -> webhooks.handle(PROVIDER, TENANT,
                BODY.getBytes(StandardCharsets.UTF_8), forged))
                .isInstanceOf(ResponseStatusException.class);

        verify(payments, never()).confirmSession(any(), any(), any());
    }

    @Test
    void refusesATimestampThatIsNotANumber() {
        assertThatThrownBy(() -> webhooks.handle(PROVIDER, TENANT,
                BODY.getBytes(StandardCharsets.UTF_8), "t=yesterday,v1=whatever"))
                .isInstanceOf(ResponseStatusException.class);

        verify(payments, never()).confirmSession(any(), any(), any());
    }

    /** The header the PSP sends: "t=<epoch millis>,v1=<base64url HMAC>". */
    private String signedAt(long millis) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signed = millis + "." + BODY;
            String v1 = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
            return "t=" + millis + ",v1=" + v1;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
