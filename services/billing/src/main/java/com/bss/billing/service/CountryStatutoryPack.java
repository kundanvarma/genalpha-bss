package com.bss.billing.service;

import com.bss.billing.exception.BadRequestException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The statutory floor under a tenant's dunning policy, keyed by country —
 * the same seam doctrine as the registry/carrier/PSP adapters. A tenant may
 * be softer than its country pack, never harder: policy writes that undercut
 * the pack are rejected. Norway ships built in (ekom + inkasso rules:
 * purregebyr only >= 14 days after due and capped, at most 2 fee-bearing
 * reminders, restriction/suspension no earlier than one month after the
 * payment demand + advance warning, a minimum actionable amount, recurring
 * charges stop while suspended for nonpayment, emergency services stay
 * reachable under any restriction). Unlisted countries get the permissive
 * default: policy is purely the tenant's.
 */
public record CountryStatutoryPack(
        String country,
        int reminderFeeGateDays,
        BigDecimal reminderFeeCap,
        int maxFeeBearingReminders,
        int enforcementNoticeDays,
        BigDecimal minActionableAmount,
        boolean mrcStopsWhileSuspended,
        boolean emergencyAlwaysReachable) {

    public static final CountryStatutoryPack NORWAY = new CountryStatutoryPack(
            "NO", 14, new BigDecimal("38.00"), 2, 30, new BigDecimal("250.00"), true, true);

    public static final CountryStatutoryPack DEFAULT = new CountryStatutoryPack(
            "??", 0, null, Integer.MAX_VALUE, 0, BigDecimal.ZERO, false, true);

    private static final Map<String, CountryStatutoryPack> PACKS = Map.of("NO", NORWAY);

    public static CountryStatutoryPack of(String country) {
        return PACKS.getOrDefault(country == null ? "" : country.toUpperCase(), DEFAULT);
    }

    /** Reject a policy that undercuts this pack. Steps come pre-parsed. */
    public void validate(List<CollectionService.Step> steps, BigDecimal entryThreshold) {
        if (entryThreshold == null || entryThreshold.compareTo(minActionableAmount) < 0) {
            throw new BadRequestException("entryThreshold must be at least the statutory minimum"
                    + " actionable amount of " + minActionableAmount + " for country " + country);
        }
        int feeSteps = 0;
        Integer warnOffset = null;
        int previousOffset = -1;
        for (CollectionService.Step step : steps) {
            if (step.offsetDays() < previousOffset) {
                throw new BadRequestException("steps must be ordered by offsetDays");
            }
            previousOffset = step.offsetDays();
            if (step.hasFee()) {
                feeSteps++;
                if (step.offsetDays() < reminderFeeGateDays) {
                    throw new BadRequestException("a fee-bearing reminder may not come before "
                            + reminderFeeGateDays + " days after the due date (country " + country + ")");
                }
                if (reminderFeeCap != null && step.feeAmount() != null
                        && step.feeAmount().compareTo(reminderFeeCap) > 0) {
                    throw new BadRequestException("the reminder fee is capped at " + reminderFeeCap
                            + " for country " + country);
                }
            }
            if ("warn".equals(step.action()) && warnOffset == null) {
                warnOffset = step.offsetDays();
            }
            if (step.isEnforcement()) {
                if (warnOffset == null) {
                    throw new BadRequestException("no restriction or suspension without a prior"
                            + " payment demand + advance warning ('warn') step");
                }
                if (step.offsetDays() < warnOffset + enforcementNoticeDays) {
                    throw new BadRequestException("restriction/suspension may come no earlier than "
                            + enforcementNoticeDays + " days after the demand + warning (country "
                            + country + ")");
                }
            }
        }
        if (feeSteps > maxFeeBearingReminders) {
            throw new BadRequestException("at most " + maxFeeBearingReminders
                    + " fee-bearing reminders are allowed (country " + country + ")");
        }
    }

    /** The floor, readable — the console renders it next to the editor. */
    public Map<String, Object> view() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("country", country);
        m.put("reminderFeeGateDays", reminderFeeGateDays);
        if (reminderFeeCap != null) {
            m.put("reminderFeeCap", reminderFeeCap);
        }
        m.put("maxFeeBearingReminders", maxFeeBearingReminders);
        m.put("enforcementNoticeDays", enforcementNoticeDays);
        m.put("minActionableAmount", minActionableAmount);
        m.put("mrcStopsWhileSuspended", mrcStopsWhileSuspended);
        m.put("emergencyAlwaysReachable", emergencyAlwaysReachable);
        return m;
    }
}
