package com.bss.campaign.service;

import com.bss.campaign.dto.AttributionReport;
import com.bss.campaign.dto.Conversions;
import com.bss.campaign.entity.Campaign;
import com.bss.campaign.entity.CampaignExecution;
import com.bss.campaign.entity.Journey;
import com.bss.campaign.entity.JourneyEnrollment;
import com.bss.campaign.repository.CampaignExecutionRepository;
import com.bss.campaign.repository.CampaignRepository;
import com.bss.campaign.repository.JourneyEnrollmentRepository;
import com.bss.campaign.repository.JourneyRepository;
import com.bss.campaign.security.TenantScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Portfolio attribution: one readout across EVERY campaign and journey, so a
 * marketer sees which programs actually moved money — not one campaign at a time.
 * Each program keeps its own holdout-vs-treated measurement (the honest kind); the
 * portfolio blends them and, critically, reports INCREMENTAL revenue — the money
 * that would not have arrived without the message (treated-per-head minus
 * holdout-per-head, times heads reached) — never the gross a message cannon
 * would claim. A program with no control group contributes reach and gross, but
 * NOT incrementality: you cannot measure lift you never left room to see.
 */
@Service
public class AttributionService {

    private final CampaignRepository campaigns;
    private final CampaignExecutionRepository executions;
    private final JourneyRepository journeys;
    private final JourneyEnrollmentRepository enrollments;
    private final TenantScope tenantScope;

    public AttributionService(CampaignRepository campaigns, CampaignExecutionRepository executions,
            JourneyRepository journeys, JourneyEnrollmentRepository enrollments, TenantScope tenantScope) {
        this.campaigns = campaigns;
        this.executions = executions;
        this.journeys = journeys;
        this.enrollments = enrollments;
        this.tenantScope = tenantScope;
    }

    @Transactional(readOnly = true)
    public AttributionReport report() {
        String tenantId = tenantScope.currentTenantId();
        List<Measured> programs = new ArrayList<>();

        for (Campaign c : campaigns.findByTenantId(tenantId)) {
            List<CampaignExecution> ex = executions.findByTenantIdAndCampaignId(tenantId, c.getId());
            long treated = ex.stream().filter(e -> !"holdout".equals(e.getVariant())).count();
            long heldOut = ex.size() - treated;
            long treatedConv = ex.stream()
                    .filter(e -> !"holdout".equals(e.getVariant()) && e.getConvertedAt() != null).count();
            long holdoutConv = ex.stream()
                    .filter(e -> "holdout".equals(e.getVariant()) && e.getConvertedAt() != null).count();
            BigDecimal treatedRev = revenue(ex.stream()
                    .filter(e -> !"holdout".equals(e.getVariant())).map(CampaignExecution::getConversionValue));
            BigDecimal holdoutRev = revenue(ex.stream()
                    .filter(e -> "holdout".equals(e.getVariant())).map(CampaignExecution::getConversionValue));
            programs.add(new Measured("campaign", c.getId(), c.getName(), c.getStatus(), c.getConversionEvent(),
                    treated, heldOut, treatedConv, holdoutConv, treatedRev, holdoutRev));
        }

        for (Journey j : journeys.findByTenantId(tenantId)) {
            List<JourneyEnrollment> en = enrollments.findByTenantIdAndJourneyId(tenantId, j.getId());
            long treated = en.stream().filter(e -> !"holdout".equals(e.getVariant())).count();
            long heldOut = en.size() - treated;
            long treatedConv = en.stream().filter(e -> !"holdout".equals(e.getVariant())
                    && "converted".equals(e.getStatus())).count();
            long holdoutConv = en.stream().filter(e -> "holdout".equals(e.getVariant())
                    && "converted".equals(e.getStatus())).count();
            BigDecimal treatedRev = revenue(en.stream()
                    .filter(e -> !"holdout".equals(e.getVariant())).map(JourneyEnrollment::getConversionValue));
            BigDecimal holdoutRev = revenue(en.stream()
                    .filter(e -> "holdout".equals(e.getVariant())).map(JourneyEnrollment::getConversionValue));
            programs.add(new Measured("journey", j.getId(), j.getName(), j.getStatus(), j.getConversionEvent(),
                    treated, heldOut, treatedConv, holdoutConv, treatedRev, holdoutRev));
        }

        // Leaderboard: the programs that earned the most attributed revenue first.
        programs.sort(Comparator.comparing((Measured m) -> m.treatedRev).reversed());

        return new AttributionReport(programs.stream().map(Measured::view).toList(), portfolio(programs),
                byChannel(programs));
    }

    private AttributionReport.Portfolio portfolio(List<Measured> programs) {
        long treated = programs.stream().mapToLong(m -> m.treated).sum();
        long heldOut = programs.stream().mapToLong(m -> m.heldOut).sum();
        long treatedConv = programs.stream().mapToLong(m -> m.treatedConv).sum();
        long holdoutConv = programs.stream().mapToLong(m -> m.holdoutConv).sum();
        BigDecimal grossTreated = programs.stream().map(m -> m.treatedRev)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal grossHoldout = programs.stream().map(m -> m.holdoutRev)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // Incremental is summed only over programs that HAVE a control group —
        // the rest have no measurable lift and must not inflate the number.
        BigDecimal incremental = programs.stream().map(Measured::incremental)
                .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);

        Double tRate = rate(treatedConv, treated);
        Double hRate = rate(holdoutConv, heldOut);
        return new AttributionReport.Portfolio(programs.size(), treated, heldOut,
                new Conversions(treatedConv, holdoutConv),
                tRate == null ? null : pct(tRate),
                hRate == null ? null : pct(hRate),
                tRate == null || hRate == null ? null : pct(tRate - hRate),
                new AttributionReport.PortfolioRevenue(grossTreated, grossHoldout, incremental,
                        "monthly recurring value of converting orders; incremental = holdout-adjusted"),
                heldOut > 0 && heldOut < 5
                        ? "portfolio holdout under 5 people — treat the blended lift as an anecdote" : null);
    }

    private Map<String, AttributionReport.ChannelTotals> byChannel(List<Measured> programs) {
        Map<String, AttributionReport.ChannelTotals> out = new LinkedHashMap<>();
        for (String channel : List.of("campaign", "journey")) {
            List<Measured> rows = programs.stream().filter(m -> m.type.equals(channel)).toList();
            if (rows.isEmpty()) {
                continue;
            }
            out.put(channel, new AttributionReport.ChannelTotals(rows.size(),
                    rows.stream().mapToLong(m -> m.treated).sum(),
                    rows.stream().map(m -> m.treatedRev).reduce(BigDecimal.ZERO, BigDecimal::add)));
        }
        return out;
    }

    private static BigDecimal revenue(java.util.stream.Stream<BigDecimal> values) {
        return values.filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static Double rate(long conv, long total) {
        return total == 0 ? null : (double) conv / total;
    }

    private static double pct(double rate) {
        return Math.round(rate * 1000) / 10.0;
    }

    /** A program's holdout-vs-treated measurement — the one honest unit of the report. */
    private record Measured(String type, String id, String name, String status, String conversionEvent,
            long treated, long heldOut, long treatedConv, long holdoutConv,
            BigDecimal treatedRev, BigDecimal holdoutRev) {

        /** Incremental revenue: treated-per-head minus holdout-per-head, times heads
         * reached — the money the message actually made. Null without a control group. */
        BigDecimal incremental() {
            if (treated == 0 || heldOut == 0) {
                return null;
            }
            BigDecimal treatedPer = treatedRev.divide(BigDecimal.valueOf(treated), 4, RoundingMode.HALF_UP);
            BigDecimal holdoutPer = holdoutRev.divide(BigDecimal.valueOf(heldOut), 4, RoundingMode.HALF_UP);
            return treatedPer.subtract(holdoutPer).multiply(BigDecimal.valueOf(treated))
                    .setScale(2, RoundingMode.HALF_UP);
        }

        AttributionReport.Program view() {
            Double tRate = rate(treatedConv, treated);
            Double hRate = rate(holdoutConv, heldOut);
            return new AttributionReport.Program(type, id, name, status, conversionEvent, treated, heldOut,
                    new Conversions(treatedConv, holdoutConv),
                    tRate == null ? null : pct(tRate),
                    hRate == null ? null : pct(hRate),
                    tRate == null || hRate == null ? null : pct(tRate - hRate),
                    treatedRev.signum() != 0 || holdoutRev.signum() != 0
                            // incremental is null when there is no control group
                            ? new AttributionReport.ProgramRevenue(treatedRev, holdoutRev, incremental()) : null);
        }
    }
}
