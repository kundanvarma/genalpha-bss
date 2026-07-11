package com.bss.intelligence;

import com.bss.intelligence.churn.ChurnAlertRepository;
import com.bss.intelligence.churn.ChurnScorer;
import com.bss.intelligence.client.BssApiClient;
import com.bss.intelligence.events.DomainEventPublisher;
import com.bss.intelligence.security.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class ChurnScorerTest {

    @Autowired
    private ChurnScorer scorer;

    @Autowired
    private ChurnAlertRepository alerts;

    @MockBean
    private BssApiClient bss;

    @MockBean
    private DomainEventPublisher events;

    @org.springframework.beans.factory.annotation.Autowired
    private com.bss.intelligence.churn.ChurnModelService modelService;

    private static Map<String, Object> agreement(String party, OffsetDateTime end) {
        return Map.of(
                "name", "12-month bundle term",
                "status", "active",
                "engagedParty", List.of(Map.of("id", party, "role", "customer")),
                "agreementPeriod", Map.of(
                        "startDateTime", end.minusMonths(12).toString(),
                        "endDateTime", end.toString()));
    }

    @Test
    void commitmentEndingSoonAlertsExactlyOnce() {
        when(bss.activeAgreements()).thenReturn(
                List.of(agreement("churn-party-1", OffsetDateTime.now().plusDays(12))));
        when(bss.usageMeters(anyString())).thenReturn(List.of());
        when(bss.ticketsOf(anyString())).thenReturn(List.of());
        when(bss.openServiceProblems()).thenReturn(List.of());

        try (TenantContext ignored = TenantContext.actAs("genalpha")) {
            assertThat(scorer.sweepCurrentTenant()).isEqualTo(1);
            assertThat(scorer.sweepCurrentTenant()).isZero(); // dedupe holds
        }
        verify(events, times(1)).publish(eq("ChurnRiskDetectedEvent"), eq("churnAlert"),
                argThat(r -> ((Map<?, ?>) r).get("state").equals(ChurnScorer.COMMITMENT_ENDING)),
                eq("genalpha"));
    }

    @Test
    void distantCommitmentsStayQuiet() {
        when(bss.activeAgreements()).thenReturn(
                List.of(agreement("churn-party-2", OffsetDateTime.now().plusMonths(10))));
        when(bss.usageMeters(anyString())).thenReturn(List.of());
        when(bss.ticketsOf(anyString())).thenReturn(List.of());
        when(bss.openServiceProblems()).thenReturn(List.of());

        try (TenantContext ignored = TenantContext.actAs("genalpha")) {
            assertThat(scorer.sweepCurrentTenant()).isZero();
        }
    }

    @Test
    void allowancePressureFiresAtNinetyPercent() {
        when(bss.activeAgreements()).thenReturn(
                List.of(agreement("churn-party-3", OffsetDateTime.now().plusMonths(10))));
        when(bss.ticketsOf(anyString())).thenReturn(List.of());
        when(bss.openServiceProblems()).thenReturn(List.of());
        when(bss.usageMeters("churn-party-3")).thenReturn(List.of(
                Map.of("name", "data", "usedValue", 46, "allowedValue", 50),
                Map.of("name", "voice", "usedValue", 10, "allowedValue", 100)));

        try (TenantContext ignored = TenantContext.actAs("genalpha")) {
            assertThat(scorer.sweepCurrentTenant()).isEqualTo(1); // data only
        }
        verify(events).publish(eq("ChurnRiskDetectedEvent"), eq("churnAlert"),
                argThat(r -> ((Map<?, ?>) r).get("state").equals(ChurnScorer.ALLOWANCE_PRESSURE)),
                eq("genalpha"));
    }

    @Test
    void supportFrictionAndOutageExposureAlert() {
        when(bss.activeAgreements()).thenReturn(
                List.of(agreement("churn-party-5", OffsetDateTime.now().plusMonths(10))));
        when(bss.usageMeters(anyString())).thenReturn(List.of());
        when(bss.openServiceProblems()).thenReturn(List.of(Map.of("id", "prob-1", "status", "open")));
        when(bss.ticketsOf("churn-party-5")).thenReturn(List.of(
                Map.of("status", "acknowledged", "creationDate", OffsetDateTime.now().minusDays(2).toString()),
                Map.of("status", "closed", "creationDate", OffsetDateTime.now().minusDays(9).toString())));

        try (TenantContext ignored = TenantContext.actAs("genalpha")) {
            // 2 recent tickets -> support-friction; open ticket during outage -> service-degraded
            assertThat(scorer.sweepCurrentTenant()).isEqualTo(2);
        }
        verify(events).publish(any(), any(),
                org.mockito.ArgumentMatchers.argThat(r -> ((Map<?, ?>) r).get("state")
                        .equals(ChurnScorer.SUPPORT_FRICTION)), eq("genalpha"));
        verify(events).publish(any(), any(),
                org.mockito.ArgumentMatchers.argThat(r -> ((Map<?, ?>) r).get("state")
                        .equals(ChurnScorer.SERVICE_DEGRADED)), eq("genalpha"));
    }

    @Test
    void aTrainedModelAddsPredictedAlerts() {
        // Train on synthetic history where imminent-expiry + tickets = churn.
        java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) {
            boolean churner = i % 2 == 0;
            rows.add(Map.of("features", List.of(churner ? 10 + i % 20 : 300 + i, 0.5,
                    churner ? 3 : 0, 0), "churned", churner));
        }
        try (TenantContext ignored = TenantContext.actAs("genalpha")) {
            modelService.trainFromImport(Map.of("rows", rows));
        }
        when(bss.openServiceProblems()).thenReturn(List.of());
        when(bss.usageMeters(anyString())).thenReturn(List.of());
        when(bss.activeAgreements()).thenReturn(
                List.of(agreement("churn-party-6", OffsetDateTime.now().plusDays(40))));
        // 40 days out: outside the 30-day RULE horizon, but the MODEL sees
        // expiry + friction together and predicts churn anyway.
        when(bss.ticketsOf("churn-party-6")).thenReturn(List.of(
                Map.of("status", "closed", "creationDate", OffsetDateTime.now().minusDays(3).toString()),
                Map.of("status", "closed", "creationDate", OffsetDateTime.now().minusDays(5).toString()),
                Map.of("status", "closed", "creationDate", OffsetDateTime.now().minusDays(8).toString())));

        try (TenantContext ignored = TenantContext.actAs("genalpha")) {
            scorer.sweepCurrentTenant();
        }
        verify(events).publish(any(), any(),
                org.mockito.ArgumentMatchers.argThat(r -> ((Map<?, ?>) r).get("state")
                        .equals(ChurnScorer.CHURN_PREDICTED)), eq("genalpha"));
    }

    @Test
    void alertsAreTenantScoped() {
        when(bss.activeAgreements()).thenReturn(
                List.of(agreement("churn-party-4", OffsetDateTime.now().plusDays(5))));
        when(bss.usageMeters(anyString())).thenReturn(List.of());
        when(bss.ticketsOf(anyString())).thenReturn(List.of());
        when(bss.openServiceProblems()).thenReturn(List.of());

        try (TenantContext a = TenantContext.actAs("tenant-a")) {
            assertThat(scorer.sweepCurrentTenant()).isEqualTo(1);
        }
        // The same fact in another tenant is a separate alert, not a dupe.
        try (TenantContext b = TenantContext.actAs("tenant-b")) {
            assertThat(scorer.sweepCurrentTenant()).isEqualTo(1);
        }
        verify(events, times(2)).publish(any(), any(), any(), anyString());
    }
}
