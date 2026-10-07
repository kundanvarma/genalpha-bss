package com.bss.entitlement;

import com.bss.entitlement.client.AucClient;
import com.bss.entitlement.client.CommunicationClient;
import com.bss.entitlement.entity.CompanionDevice;
import com.bss.entitlement.events.DomainEventPublisher;
import com.bss.entitlement.repository.CompanionDeviceRepository;
import com.bss.entitlement.repository.EcsRequestRepository;
import com.bss.entitlement.repository.EntitlementDeviceRepository;
import com.bss.entitlement.repository.EntitlementSubscriberRepository;
import com.bss.entitlement.repository.SubscriptionTransferRepository;
import com.bss.entitlement.security.TenantScope;
import com.bss.entitlement.service.EntitlementDecisionService;
import com.bss.entitlement.service.SubscriberService;
import com.bss.entitlement.service.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * SGP.22 ES2+ handleDownloadProgressInfo names BOTH identifiers: the ICCID
 * identifies the profile, and the EID identifies the eUICC it was downloaded
 * onto. profileProgress() used to match on the ICCID alone and ignore the EID
 * it was handed -- CodeQL noticed it as an unused parameter, which is how a
 * missing authorization check can look.
 *
 * OdsaService stores the EID on the companion before ordering the profile
 * (`companion_terminal_eid`), so the chip the profile was meant for is already
 * known when the SM-DP+ reports back. Without the check, a callback naming a
 * valid ICCID and a different eUICC marked that companion ACTIVE.
 */
class Es2PlusEidMatchTest {

    private CompanionDeviceRepository companions;
    private SubscriberService service;

    @BeforeEach
    void setUp() {
        companions = mock(CompanionDeviceRepository.class);
        EntitlementSubscriberRepository subscribers = mock(EntitlementSubscriberRepository.class);
        SubscriptionTransferRepository transfers = mock(SubscriptionTransferRepository.class);
        given(subscribers.findByTenantIdAndImsi(anyString(), anyString())).willReturn(Optional.empty());
        given(transfers.findTop200ByTenantIdOrderByCreatedAtDesc(anyString())).willReturn(List.of());
        given(companions.save(any())).willAnswer(i -> i.getArgument(0));

        service = new SubscriberService(subscribers, mock(EntitlementDeviceRepository.class),
                companions, mock(EcsRequestRepository.class), mock(EntitlementDecisionService.class),
                mock(TokenService.class), mock(AucClient.class), mock(DomainEventPublisher.class),
                mock(TenantScope.class), transfers, mock(CommunicationClient.class));
    }

    private CompanionDevice companionOn(String iccid, String eid) {
        CompanionDevice c = new CompanionDevice();
        c.setId("c-1");
        c.setTenantId("genalpha");
        c.setIccid(iccid);
        c.setEid(eid);
        c.setImsi("242011234567890");
        c.setStatus("DISABLED");
        given(companions.findTop200ByTenantIdOrderByLastUpdateDesc("genalpha")).willReturn(List.of(c));
        return c;
    }

    @Test
    void theRightChipInstallsAndActivates() {
        CompanionDevice c = companionOn("8947000000000000001", "89049032000001000000000012345678");

        service.profileProgress("genalpha", "8947000000000000001",
                "89049032000001000000000012345678", 4, "Executed-Success");

        assertThat(c.getProfileState()).isEqualTo("installed");
        assertThat(c.getStatus()).isEqualTo(CompanionDevice.ACTIVE);
    }

    @Test
    void aDifferentChipWithTheRightIccidChangesNothing() {
        CompanionDevice c = companionOn("8947000000000000001", "89049032000001000000000012345678");

        service.profileProgress("genalpha", "8947000000000000001",
                "89049032000001000000000099999999", 4, "Executed-Success");

        assertThat(c.getStatus()).isEqualTo("DISABLED");
        assertThat(c.getProfileState()).isNull();
    }

    /**
     * The EID is not always known at download time, so an absent one on either
     * side still matches on the ICCID. Tightening that would break the ordinary
     * path rather than any attack.
     */
    @Test
    void anUnknownEidStillMatchesOnTheIccid() {
        CompanionDevice c = companionOn("8947000000000000001", null);

        service.profileProgress("genalpha", "8947000000000000001",
                "89049032000001000000000012345678", 4, "Executed-Success");

        assertThat(c.getStatus()).isEqualTo(CompanionDevice.ACTIVE);
    }

    @Test
    void aCallbackWithNoEidStillMatchesOnTheIccid() {
        CompanionDevice c = companionOn("8947000000000000001", "89049032000001000000000012345678");

        service.profileProgress("genalpha", "8947000000000000001", null, 4, "Executed-Success");

        assertThat(c.getStatus()).isEqualTo(CompanionDevice.ACTIVE);
    }
}
