package com.bss.payment;

import com.bss.payment.client.PaymentMethodClient;
import com.bss.payment.entity.Payment;
import com.bss.payment.entity.PspConfig;
import com.bss.payment.entity.PspRedirectSession;
import com.bss.payment.events.DomainEventPublisher;
import com.bss.payment.exception.NotFoundException;
import com.bss.payment.psp.PspRouter;
import com.bss.payment.psp.RedirectPspAdapter;
import com.bss.payment.psp.RedirectPspRegistry;
import com.bss.payment.repository.PaymentRepository;
import com.bss.payment.repository.PspRedirectSessionRepository;
import com.bss.payment.security.PartyScope;
import com.bss.payment.security.TenantRegistry;
import com.bss.payment.security.TenantScope;
import com.bss.payment.service.PaymentService;
import com.bss.payment.service.PspConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * A redirect payment session used to have no owner until it was confirmed.
 *
 * `startRedirect` asked the PSP for a session and persisted nothing; the Payment
 * row appeared only at confirm, and its owner came from whoever was confirming:
 *
 *     entity.setOwnerPartyId(partyScope.scopedPartyId().orElse(null));
 *
 * Nothing compared that against whoever STARTED the session, because there was
 * nothing to compare against. So anyone holding a session id could call the
 * return leg and receive an AUTHORIZED payment owned by themselves, for money
 * the real customer had paid — and the idempotency on session_ref then made it
 * stick, because the victim's own return leg found the existing row.
 *
 * The id is long and random and comes from the PSP, so guessing is not the path.
 * It travels in a URL the customer's browser visits, and URLs leak through
 * referrers, history, shared screens and access logs.
 *
 * Four cases, because the middle two are the fix and the outer two are what the
 * fix must not break. Issue #250.
 */
class RedirectSessionOwnerTest {

    private static final String TENANT = "genalpha";
    private static final String PROVIDER = "klarna";
    private static final String SESSION = "sess_live_7f3a9c21d4";
    private static final String PAULA = "party-paula";
    private static final String MALLORY = "party-mallory";

    private PaymentRepository payments;
    private PspRedirectSessionRepository sessions;
    private PartyScope partyScope;
    private PaymentService service;

    @BeforeEach
    void setUp() {
        payments = mock(PaymentRepository.class);
        sessions = mock(PspRedirectSessionRepository.class);
        partyScope = mock(PartyScope.class);
        PspConfigService configs = mock(PspConfigService.class);
        RedirectPspRegistry registry = mock(RedirectPspRegistry.class);
        RedirectPspAdapter adapter = mock(RedirectPspAdapter.class);

        PspConfig cfg = new PspConfig();
        cfg.setTenantId(TENANT);
        cfg.setProvider(PROVIDER);
        given(configs.forTenantAndProvider(TENANT, PROVIDER)).willReturn(Optional.of(cfg));
        given(registry.get(PROVIDER)).willReturn(adapter);
        // the PSP says the money moved — it is the authority on that, and on
        // nothing else. Whose money it was is ours to know.
        given(adapter.confirm(any(), anyString())).willReturn(new RedirectPspAdapter.Confirmation(
                true, new BigDecimal("499.00"), "NOK", "auth-1", "Klarna", null));

        given(payments.findFirstByTenantIdAndSessionRef(anyString(), anyString()))
                .willReturn(Optional.empty());
        given(payments.save(any())).willAnswer(i -> i.getArgument(0));

        service = new PaymentService(payments, mock(PspRouter.class), registry, configs,
                mock(PaymentMethodClient.class), mock(DomainEventPublisher.class),
                partyScope, mock(TenantScope.class), mock(TenantRegistry.class), sessions);
    }

    private void sessionStartedBy(String owner) {
        PspRedirectSession row = new PspRedirectSession();
        row.setId("s-1");
        row.setTenantId(TENANT);
        row.setSessionRef(SESSION);
        row.setProvider(PROVIDER);
        row.setOwnerPartyId(owner);
        row.setCreatedAt(OffsetDateTime.now());
        given(sessions.findByTenantIdAndSessionRef(TENANT, SESSION)).willReturn(Optional.of(row));
    }

    private void confirmingAs(String party) {
        given(partyScope.scopedPartyId())
                .willReturn(party == null ? Optional.empty() : Optional.of(party));
    }

    @Test
    void theCustomerWhoStartedItGetsThePayment() {
        sessionStartedBy(PAULA);
        confirmingAs(PAULA);

        assertThat(service.confirmSession(TENANT, PROVIDER, SESSION).getId()).isNotBlank();
        assertThat(savedPayment().getOwnerPartyId()).isEqualTo(PAULA);
    }

    /** The finding: a leaked session id must not become somebody else's payment. */
    @Test
    void anotherCustomerQuotingTheSessionIsRefused() {
        sessionStartedBy(PAULA);
        confirmingAs(MALLORY);

        assertThatThrownBy(() -> service.confirmSession(TENANT, PROVIDER, SESSION))
                .isInstanceOf(NotFoundException.class);
    }

    /**
     * The webhook is the PSP talking, authenticated by HMAC, and has no party
     * scope at all. "No scope" has to mean "use the recorded owner", never
     * "refuse" — otherwise the fix breaks the path that does most of the work.
     */
    @Test
    void theWebhookConfirmsOnBehalfOfTheRecordedOwner() {
        sessionStartedBy(PAULA);
        confirmingAs(null);

        service.confirmSession(TENANT, PROVIDER, SESSION);

        assertThat(savedPayment().getOwnerPartyId()).isEqualTo(PAULA);
    }

    /**
     * A guest checkout has no party when the session starts, so the recorded
     * owner is null. That must not read as "anyone's": the payment simply has
     * no owner, as it did before.
     */
    @Test
    void aGuestSessionStaysOwnerless() {
        sessionStartedBy(null);
        confirmingAs(null);

        service.confirmSession(TENANT, PROVIDER, SESSION);

        assertThat(savedPayment().getOwnerPartyId()).isNull();
    }

    /**
     * Sessions started before this shipped have no row. They must still
     * confirm — a redirect session lives minutes, so this fades on its own —
     * and the service logs it, because a steady trickle after a deploy would
     * mean the recording side is failing.
     */
    @Test
    void aSessionWithNoRecordStillConfirms() {
        given(sessions.findByTenantIdAndSessionRef(TENANT, SESSION)).willReturn(Optional.empty());
        confirmingAs(PAULA);

        service.confirmSession(TENANT, PROVIDER, SESSION);

        assertThat(savedPayment().getOwnerPartyId()).isEqualTo(PAULA);
    }

    private Payment savedPayment() {
        org.mockito.ArgumentCaptor<Payment> captor = org.mockito.ArgumentCaptor.forClass(Payment.class);
        org.mockito.Mockito.verify(payments).save(captor.capture());
        return captor.getValue();
    }
}
