package com.bss.devicecommerce.service;

import com.bss.devicecommerce.api.ApiConstants;
import com.bss.devicecommerce.client.PaymentClient;
import com.bss.devicecommerce.entity.DeviceAgreement;
import com.bss.devicecommerce.entity.DeviceFlag;
import com.bss.devicecommerce.entity.GradingEvent;
import com.bss.devicecommerce.entity.TradeInResidual;
import com.bss.devicecommerce.entity.TradeInValuation;
import com.bss.devicecommerce.events.DomainEventPublisher;
import com.bss.devicecommerce.exception.BadRequestException;
import com.bss.devicecommerce.exception.ConflictException;
import com.bss.devicecommerce.exception.NotFoundException;
import com.bss.devicecommerce.repository.DeviceAgreementRepository;
import com.bss.devicecommerce.repository.DeviceFlagRepository;
import com.bss.devicecommerce.repository.GradingEventRepository;
import com.bss.devicecommerce.repository.TradeInResidualRepository;
import com.bss.devicecommerce.repository.TradeInValuationRepository;
import com.bss.devicecommerce.security.PartyScope;
import com.bss.devicecommerce.security.TenantScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Trade-in: IMEI + guided condition answers → instant estimate off the
 * tenant's residual table (a blacklisted IMEI quotes ZERO); accept →
 * mail-in → partner grading → delta. Positive delta refunds through the
 * payment component; negative delta becomes a DeviceChargeRequestedEvent
 * (billing's seam) and waits for the customer to accept or reject the
 * revised value — a rejected revision returns the device.
 */
@Service
public class TradeInService {

    private static final Logger log = LoggerFactory.getLogger(TradeInService.class);

    /** Guided-assessment defects and the share of value each one costs.
     * Deterministic and tenant-visible in the response — no black box. */
    private static final Map<String, BigDecimal> DEFECT_HAIRCUTS = Map.of(
            "screenCracked", new BigDecimal("0.40"),
            "backCracked", new BigDecimal("0.20"),
            "batteryWorn", new BigDecimal("0.15"),
            "notPoweringOn", new BigDecimal("0.80"));

    private final TradeInValuationRepository valuations;
    private final GradingEventRepository gradings;
    private final TradeInResidualRepository residuals;
    private final DeviceFlagRepository flags;
    private final DeviceAgreementRepository agreements;
    private final PaymentClient payments;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;
    private final PartyScope partyScope;
    private final ObjectMapper objectMapper;
    private final int offerDays;

    public TradeInService(TradeInValuationRepository valuations, GradingEventRepository gradings,
            TradeInResidualRepository residuals, DeviceFlagRepository flags,
            DeviceAgreementRepository agreements, PaymentClient payments,
            DomainEventPublisher events, TenantScope tenantScope, PartyScope partyScope,
            ObjectMapper objectMapper, @Value("${bss.device.offer-days:30}") int offerDays) {
        this.valuations = valuations;
        this.gradings = gradings;
        this.residuals = residuals;
        this.flags = flags;
        this.agreements = agreements;
        this.payments = payments;
        this.events = events;
        this.tenantScope = tenantScope;
        this.partyScope = partyScope;
        this.objectMapper = objectMapper;
        this.offerDays = offerDays;
    }

    /* ---------- the valuation flow ---------- */

    @Transactional
    public Map<String, Object> quote(Map<String, Object> dto) {
        if (dto.get("imei") == null || dto.get("deviceRef") == null) {
            throw new BadRequestException("imei and deviceRef are required");
        }
        String tenant = tenantScope.currentTenantId();
        TradeInValuation v = new TradeInValuation();
        v.setId(UUID.randomUUID().toString());
        v.setTenantId(tenant);
        v.setHref(ApiConstants.BASE_PATH + "/tradeInValuation/" + v.getId());
        v.setPartyId(relatedPartyId(dto));
        partyScope.scopedPartyId().ifPresent(v::setPartyId);
        v.setImei(String.valueOf(dto.get("imei")).replaceAll("\\s", ""));
        v.setDeviceRef(String.valueOf(dto.get("deviceRef")));
        Map<String, Object> answers = dto.get("conditionAnswers") instanceof Map<?, ?> m
                ? castMap(m) : Map.of();
        v.setConditionJson(writeJson(answers));
        boolean blacklisted = flags.existsByTenantIdAndImeiAndFlag(tenant, v.getImei(),
                DeviceFlag.BLACKLISTED);
        Estimate estimate = blacklisted
                ? new Estimate(BigDecimal.ZERO, "EUR", "IMEI is blacklisted — a flagged device is worth zero")
                : estimate(tenant, v.getDeviceRef(), answers);
        v.setEstimatedValue(estimate.value());
        v.setCurrency(estimate.currency());
        v.setOfferExpiry(OffsetDateTime.now().plusDays(offerDays));
        v.setChannel(dto.get("channel") == null ? "shop" : String.valueOf(dto.get("channel")));
        v.setPaymentRef(dto.get("paymentRef") == null ? null : String.valueOf(dto.get("paymentRef")));
        v.setAgreementRef(dto.get("agreementRef") == null ? null : String.valueOf(dto.get("agreementRef")));
        v.setStatus(TradeInValuation.QUOTED);
        v.setCreatedAt(OffsetDateTime.now());
        v.setLastUpdate(OffsetDateTime.now());
        valuations.save(v);
        Map<String, Object> view = view(v);
        view.put("note", estimate.note());
        events.publish("TradeInQuoted", "tradeInValuation", view);
        log.info("trade-in {} quoted {} {} for {} ({})", v.getId(), v.getEstimatedValue(),
                v.getCurrency(), v.getDeviceRef(), blacklisted ? "BLACKLISTED" : "clean");
        return view;
    }

    @Transactional
    public Map<String, Object> accept(String id) {
        TradeInValuation v = own(id);
        if (TradeInValuation.ACCEPTED.equals(v.getStatus())) {
            return view(v);   // idempotent
        }
        if (!TradeInValuation.QUOTED.equals(v.getStatus())) {
            throw new ConflictException("only quoted valuations accept (is " + v.getStatus() + ")");
        }
        if (v.getOfferExpiry().isBefore(OffsetDateTime.now())) {
            throw new ConflictException("the offer expired " + v.getOfferExpiry() + " — quote again");
        }
        v.setStatus(TradeInValuation.ACCEPTED);
        v.setLastUpdate(OffsetDateTime.now());
        valuations.save(v);
        Map<String, Object> view = view(v);
        events.publish("TradeInAccepted", "tradeInValuation", view);
        return view;
    }

    /** The device is on its way (drop-off scan / return-parcel event). */
    @Transactional
    public Map<String, Object> inTransit(String id) {
        TradeInValuation v = own(id);
        if (TradeInValuation.IN_TRANSIT.equals(v.getStatus())) {
            return view(v);
        }
        if (!TradeInValuation.ACCEPTED.equals(v.getStatus())) {
            throw new ConflictException("only accepted valuations go in-transit (is " + v.getStatus() + ")");
        }
        v.setStatus(TradeInValuation.IN_TRANSIT);
        v.setLastUpdate(OffsetDateTime.now());
        valuations.save(v);
        return view(v);
    }

    /**
     * The grading partner's verdict (staff/machine). Delta over the estimate:
     * positive refunds through the PSP path immediately (nobody rejects more
     * money); negative revalues and WAITS — the customer accepts the revised
     * value or takes the device back.
     */
    @Transactional
    public Map<String, Object> grade(String id, Map<String, Object> dto) {
        TradeInValuation v = own(id);
        if (!List.of(TradeInValuation.ACCEPTED, TradeInValuation.IN_TRANSIT).contains(v.getStatus())) {
            throw new ConflictException("grading needs an accepted/in-transit valuation (is "
                    + v.getStatus() + ")");
        }
        if (dto == null || dto.get("finalValue") == null) {
            throw new BadRequestException("finalValue is required");
        }
        BigDecimal finalValue = money(dto.get("finalValue"));
        BigDecimal delta = finalValue.subtract(v.getEstimatedValue());
        GradingEvent g = new GradingEvent();
        g.setId(UUID.randomUUID().toString());
        g.setTenantId(v.getTenantId());
        g.setValuationRef(v.getId());
        g.setPartnerRef(dto.get("partnerRef") == null ? "in-house" : String.valueOf(dto.get("partnerRef")));
        g.setFinalGrade(dto.get("finalGrade") == null ? null : String.valueOf(dto.get("finalGrade")));
        g.setFinalValue(finalValue);
        g.setDelta(delta);
        g.setNote(dto.get("note") == null ? null : String.valueOf(dto.get("note")));
        g.setCreatedAt(OffsetDateTime.now());
        gradings.save(g);

        v.setFinalValue(finalValue);
        v.setDelta(delta);
        v.setStatus(TradeInValuation.GRADED);
        v.setLastUpdate(OffsetDateTime.now());
        events.publish("TradeInGraded", "tradeInValuation", view(v));

        if (delta.signum() == 0) {
            v.setStatus(TradeInValuation.SETTLED);
            valuations.save(v);
            Map<String, Object> view = view(v);
            events.publish("TradeInSettled", "tradeInValuation", view);
            return view;
        }
        v.setStatus(TradeInValuation.REVALUED);
        valuations.save(v);
        events.publish("TradeInRevalued", "tradeInValuation", view(v));
        if (delta.signum() > 0) {
            settleRefund(v, delta);
        } else {
            // the charge is billing's to collect — we publish the request and
            // expose the delta on the linked agreement while it waits
            Map<String, Object> charge = new LinkedHashMap<>();
            charge.put("tradeInValuationId", v.getId());
            charge.put("amount", delta.negate());
            charge.put("currency", v.getCurrency());
            if (v.getPartyId() != null) {
                charge.put("relatedParty", List.of(Map.of("id", v.getPartyId(), "role", "customer")));
            }
            charge.put("@type", "DeviceChargeRequest");
            events.publish("DeviceChargeRequestedEvent", "deviceChargeRequest", charge);
            linkedAgreement(v).ifPresent(a -> {
                a.setTradeInDelta(delta);
                a.setLastUpdate(OffsetDateTime.now());
                agreements.save(a);
            });
        }
        return view(v);
    }

    /** The customer takes the revised (lower) value — money moves, done. */
    @Transactional
    public Map<String, Object> acceptRevaluation(String id) {
        TradeInValuation v = own(id);
        if (TradeInValuation.SETTLED.equals(v.getStatus())) {
            return view(v);
        }
        if (!TradeInValuation.REVALUED.equals(v.getStatus())) {
            throw new ConflictException("nothing to accept — valuation is " + v.getStatus());
        }
        v.setStatus(TradeInValuation.SETTLED);
        v.setLastUpdate(OffsetDateTime.now());
        valuations.save(v);
        clearAgreementDelta(v);
        Map<String, Object> view = view(v);
        events.publish("TradeInSettled", "tradeInValuation", view);
        return view;
    }

    /** The customer refuses the revised value — the device goes back. */
    @Transactional
    public Map<String, Object> rejectRevaluation(String id) {
        TradeInValuation v = own(id);
        if (TradeInValuation.REJECTED_RETURNED.equals(v.getStatus())) {
            return view(v);
        }
        if (!TradeInValuation.REVALUED.equals(v.getStatus())) {
            throw new ConflictException("nothing to reject — valuation is " + v.getStatus());
        }
        v.setStatus(TradeInValuation.REJECTED_RETURNED);
        v.setLastUpdate(OffsetDateTime.now());
        valuations.save(v);
        clearAgreementDelta(v);
        return view(v);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findAll(String relatedPartyId, String status) {
        String scoped = partyScope.scopedPartyId().orElse(relatedPartyId);
        return valuations.findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId()).stream()
                .filter(v -> scoped == null || scoped.equals(v.getPartyId()))
                .filter(v -> status == null || status.equals(v.getStatus()))
                .map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findById(String id) {
        TradeInValuation v = own(id);
        Map<String, Object> view = view(v);
        List<Map<String, Object>> history = gradings
                .findByTenantIdAndValuationRefOrderByCreatedAtAsc(v.getTenantId(), v.getId())
                .stream().map(this::gradingView).toList();
        if (!history.isEmpty()) {
            view.put("gradingEvent", history);
        }
        return view;
    }

    /* ---------- the residual table (staff-curated) ---------- */

    @Transactional
    public Map<String, Object> upsertResidual(Map<String, Object> dto) {
        for (String required : List.of("deviceRef", "ageMonths", "baseValue")) {
            if (dto.get(required) == null) {
                throw new BadRequestException(required + " is required");
            }
        }
        String tenant = tenantScope.currentTenantId();
        String deviceRef = String.valueOf(dto.get("deviceRef"));
        int ageMonths = Integer.parseInt(String.valueOf(dto.get("ageMonths")));
        TradeInResidual row = residuals
                .findByTenantIdAndDeviceRefOrderByAgeMonthsAsc(tenant, deviceRef).stream()
                .filter(r -> r.getAgeMonths() == ageMonths)
                .findFirst().orElseGet(() -> {
                    TradeInResidual fresh = new TradeInResidual();
                    fresh.setId(UUID.randomUUID().toString());
                    fresh.setTenantId(tenant);
                    fresh.setDeviceRef(deviceRef);
                    fresh.setAgeMonths(ageMonths);
                    fresh.setCreatedAt(OffsetDateTime.now());
                    return fresh;
                });
        row.setBaseValue(money(dto.get("baseValue")));
        row.setCurrency(dto.get("currency") == null ? "EUR" : String.valueOf(dto.get("currency")));
        row.setLastUpdate(OffsetDateTime.now());
        residuals.save(row);
        return residualView(row);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> residualTable(String deviceRef) {
        String tenant = tenantScope.currentTenantId();
        List<TradeInResidual> rows = deviceRef == null
                ? residuals.findByTenantIdOrderByDeviceRefAscAgeMonthsAsc(tenant)
                : residuals.findByTenantIdAndDeviceRefOrderByAgeMonthsAsc(tenant, deviceRef);
        return rows.stream().map(this::residualView).toList();
    }

    @Transactional
    public void deleteResidual(String id) {
        TradeInResidual row = residuals.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("TradeInResidual", id));
        residuals.delete(row);
    }

    /* ---------- the blacklist flag stub ---------- */

    @Transactional
    public Map<String, Object> flag(Map<String, Object> dto) {
        if (dto.get("imei") == null) {
            throw new BadRequestException("imei is required");
        }
        return flagImei(String.valueOf(dto.get("imei")).replaceAll("\\s", ""),
                dto.get("reason") == null ? "lost" : String.valueOf(dto.get("reason")),
                dto.get("sourceRef") == null ? "manual" : String.valueOf(dto.get("sourceRef")));
    }

    /** Also the SIM-block listener's entry point (already tenant-contexted). */
    @Transactional
    public Map<String, Object> flagImei(String imei, String reason, String sourceRef) {
        String tenant = tenantScope.currentTenantId();
        if (flags.existsByTenantIdAndImeiAndFlag(tenant, imei, DeviceFlag.BLACKLISTED)) {
            return flagView(flags.findByTenantIdAndImei(tenant, imei).get(0));   // idempotent
        }
        DeviceFlag f = new DeviceFlag();
        f.setId(UUID.randomUUID().toString());
        f.setTenantId(tenant);
        f.setImei(imei);
        f.setFlag(DeviceFlag.BLACKLISTED);
        f.setReason(reason);
        f.setSourceRef(sourceRef);
        f.setCreatedAt(OffsetDateTime.now());
        flags.save(f);
        Map<String, Object> view = flagView(f);
        // the registry push (EIR/GSMA) is an adapter someone deploys; the
        // EVENT is the seam — mock deployments stop here, honestly
        events.publish("DeviceBlacklistRequested", "deviceFlag", view);
        log.info("device flag: IMEI {} blacklisted ({}, {})", imei, reason, sourceRef);
        return view;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> flagsOf(String imei) {
        String tenant = tenantScope.currentTenantId();
        List<DeviceFlag> rows = imei == null
                ? flags.findByTenantIdOrderByCreatedAtDesc(tenant)
                : flags.findByTenantIdAndImei(tenant, imei);
        return rows.stream().map(this::flagView).toList();
    }

    /* ---------- internals ---------- */

    private record Estimate(BigDecimal value, String currency, String note) {
    }

    /** Base value = the residual row at the device's age (nearest not-younger
     * row wins); each declared defect takes its published haircut. */
    private Estimate estimate(String tenant, String deviceRef, Map<String, Object> answers) {
        List<TradeInResidual> rows = residuals.findByTenantIdAndDeviceRefOrderByAgeMonthsAsc(tenant, deviceRef);
        if (rows.isEmpty()) {
            return new Estimate(BigDecimal.ZERO, "EUR",
                    "no residual row for '" + deviceRef + "' — staff curate the table");
        }
        int age = answers.get("ageMonths") == null ? 0
                : Integer.parseInt(String.valueOf(answers.get("ageMonths")));
        TradeInResidual match = rows.get(0);
        for (TradeInResidual row : rows) {
            if (row.getAgeMonths() <= age) {
                match = row;
            }
        }
        BigDecimal haircut = BigDecimal.ZERO;
        StringBuilder note = new StringBuilder("base " + match.getBaseValue()
                + " at age " + match.getAgeMonths() + "m");
        for (Map.Entry<String, BigDecimal> defect : DEFECT_HAIRCUTS.entrySet()) {
            if (Boolean.parseBoolean(String.valueOf(answers.get(defect.getKey())))) {
                haircut = haircut.add(defect.getValue());
                note.append(", ").append(defect.getKey()).append(" −")
                        .append(defect.getValue().movePointRight(2).stripTrailingZeros().toPlainString())
                        .append('%');
            }
        }
        BigDecimal factor = BigDecimal.ONE.subtract(haircut);
        BigDecimal value = factor.signum() <= 0 ? BigDecimal.ZERO
                : match.getBaseValue().multiply(factor).setScale(2, RoundingMode.HALF_UP);
        return new Estimate(value, match.getCurrency(), note.toString());
    }

    /** Positive delta: money back the way it came (PSP path), fail-soft. */
    private void settleRefund(TradeInValuation v, BigDecimal delta) {
        String paymentRef = v.getPaymentRef();
        if (paymentRef == null) {
            paymentRef = linkedAgreement(v).map(DeviceAgreement::getPaymentRef).orElse(null);
        }
        String refundRef = paymentRef == null ? null
                : payments.refund(paymentRef, delta, "trade-in revaluation " + v.getId());
        v.setRefundRef(refundRef);
        v.setStatus(TradeInValuation.SETTLED);
        v.setLastUpdate(OffsetDateTime.now());
        valuations.save(v);
        Map<String, Object> view = view(v);
        if (refundRef == null) {
            view.put("note", "no reachable payment to refund against — delta stays owed to the customer");
        }
        events.publish("TradeInSettled", "tradeInValuation", view);
    }

    private java.util.Optional<DeviceAgreement> linkedAgreement(TradeInValuation v) {
        if (v.getAgreementRef() == null) {
            return java.util.Optional.empty();
        }
        return agreements.findByIdAndTenantId(v.getAgreementRef(), v.getTenantId());
    }

    private void clearAgreementDelta(TradeInValuation v) {
        linkedAgreement(v).ifPresent(a -> {
            a.setTradeInDelta(null);
            a.setLastUpdate(OffsetDateTime.now());
            agreements.save(a);
        });
    }

    private TradeInValuation own(String id) {
        TradeInValuation v = valuations.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("TradeInValuation", id));
        partyScope.scopedPartyId().ifPresent(party -> {
            if (!party.equals(v.getPartyId())) {
                throw NotFoundException.forResource("TradeInValuation", id);
            }
        });
        return v;
    }

    private Map<String, Object> view(TradeInValuation v) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", v.getId());
        map.put("href", v.getHref());
        map.put("imei", v.getImei());
        map.put("deviceRef", v.getDeviceRef());
        map.put("status", v.getStatus());
        map.put("estimatedValue", v.getEstimatedValue());
        if (v.getFinalValue() != null) map.put("finalValue", v.getFinalValue());
        if (v.getDelta() != null) map.put("delta", v.getDelta());
        map.put("currency", v.getCurrency());
        map.put("offerExpiry", v.getOfferExpiry().toString());
        if (v.getChannel() != null) map.put("channel", v.getChannel());
        if (v.getConditionJson() != null) map.put("conditionAnswers", readJson(v.getConditionJson()));
        if (v.getPaymentRef() != null) map.put("paymentRef", v.getPaymentRef());
        if (v.getRefundRef() != null) map.put("refundRef", v.getRefundRef());
        if (v.getAgreementRef() != null) map.put("agreementRef", v.getAgreementRef());
        if (v.getPartyId() != null) {
            map.put("relatedParty", List.of(Map.of("id", v.getPartyId(), "role", "customer")));
        }
        map.put("@type", "TradeInValuation");
        return map;
    }

    private Map<String, Object> gradingView(GradingEvent g) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", g.getId());
        map.put("partnerRef", g.getPartnerRef());
        if (g.getFinalGrade() != null) map.put("finalGrade", g.getFinalGrade());
        map.put("finalValue", g.getFinalValue());
        map.put("delta", g.getDelta());
        if (g.getNote() != null) map.put("note", g.getNote());
        map.put("createdAt", g.getCreatedAt().toString());
        return map;
    }

    private Map<String, Object> residualView(TradeInResidual r) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", r.getId());
        map.put("deviceRef", r.getDeviceRef());
        map.put("ageMonths", r.getAgeMonths());
        map.put("baseValue", r.getBaseValue());
        map.put("currency", r.getCurrency());
        map.put("@type", "TradeInResidual");
        return map;
    }

    private Map<String, Object> flagView(DeviceFlag f) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", f.getId());
        map.put("imei", f.getImei());
        map.put("flag", f.getFlag());
        if (f.getReason() != null) map.put("reason", f.getReason());
        if (f.getSourceRef() != null) map.put("sourceRef", f.getSourceRef());
        map.put("createdAt", f.getCreatedAt().toString());
        map.put("@type", "DeviceFlag");
        return map;
    }

    private static String relatedPartyId(Map<String, Object> dto) {
        if (dto.get("relatedParty") instanceof List<?> parties && !parties.isEmpty()
                && parties.get(0) instanceof Map<?, ?> party && party.get("id") != null) {
            return String.valueOf(party.get("id"));
        }
        return null;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Object readJson(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object m) {
        return (Map<String, Object>) m;
    }

    private static BigDecimal money(Object v) {
        try {
            return new BigDecimal(String.valueOf(v)).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            throw new BadRequestException("'" + v + "' is not an amount");
        }
    }
}
