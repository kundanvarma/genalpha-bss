package com.bss.devicecommerce.service;

import com.bss.devicecommerce.api.ApiConstants;
import com.bss.devicecommerce.client.PaymentClient;
import com.bss.devicecommerce.dto.DeviceChargeRequest;
import com.bss.devicecommerce.dto.DeviceFlagRequest;
import com.bss.devicecommerce.dto.DeviceFlagView;
import com.bss.devicecommerce.dto.GradingRequest;
import com.bss.devicecommerce.dto.ResidualRequest;
import com.bss.devicecommerce.dto.TradeInQuoteRequest;
import com.bss.devicecommerce.dto.TradeInResidualView;
import com.bss.devicecommerce.dto.TradeInValuationView;
import com.bss.devicecommerce.entity.DeviceAgreement;
import com.bss.devicecommerce.entity.DeviceFlag;
import com.bss.devicecommerce.entity.GradingEvent;
import com.bss.devicecommerce.entity.TradeInResidual;
import com.bss.devicecommerce.entity.TradeInValuation;
import com.bss.devicecommerce.events.DomainEventPublisher;
import com.bss.devicecommerce.exception.BadRequestException;
import com.bss.devicecommerce.exception.ConflictException;
import com.bss.devicecommerce.exception.NotFoundException;
import com.bss.devicecommerce.mapper.TradeInMapper;
import com.bss.devicecommerce.repository.DeviceAgreementRepository;
import com.bss.devicecommerce.repository.DeviceFlagRepository;
import com.bss.devicecommerce.repository.GradingEventRepository;
import com.bss.devicecommerce.repository.TradeInResidualRepository;
import com.bss.devicecommerce.repository.TradeInValuationRepository;
import com.bss.devicecommerce.security.PartyScope;
import com.bss.devicecommerce.security.TenantScope;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Optional;
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
     * Deterministic and tenant-visible in the response — no black box. In
     * declaration order, so the estimate's note lists them the same way on
     * every JVM (a {@code Map.of} had been listing them at random). */
    private static final Map<String, BigDecimal> DEFECT_HAIRCUTS = new LinkedHashMap<>();

    static {
        DEFECT_HAIRCUTS.put("screenCracked", new BigDecimal("0.40"));
        DEFECT_HAIRCUTS.put("backCracked", new BigDecimal("0.20"));
        DEFECT_HAIRCUTS.put("batteryWorn", new BigDecimal("0.15"));
        DEFECT_HAIRCUTS.put("notPoweringOn", new BigDecimal("0.80"));
    }

    private final TradeInValuationRepository valuations;
    private final GradingEventRepository gradings;
    private final TradeInResidualRepository residuals;
    private final DeviceFlagRepository flags;
    private final DeviceAgreementRepository agreements;
    private final PaymentClient payments;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;
    private final PartyScope partyScope;
    private final TradeInMapper mapper;
    private final int offerDays;

    public TradeInService(TradeInValuationRepository valuations, GradingEventRepository gradings,
            TradeInResidualRepository residuals, DeviceFlagRepository flags,
            DeviceAgreementRepository agreements, PaymentClient payments,
            DomainEventPublisher events, TenantScope tenantScope, PartyScope partyScope,
            TradeInMapper mapper, @Value("${bss.device.offer-days:30}") int offerDays) {
        this.valuations = valuations;
        this.gradings = gradings;
        this.residuals = residuals;
        this.flags = flags;
        this.agreements = agreements;
        this.payments = payments;
        this.events = events;
        this.tenantScope = tenantScope;
        this.partyScope = partyScope;
        this.mapper = mapper;
        this.offerDays = offerDays;
    }

    /* ---------- the valuation flow ---------- */

    @Transactional
    public TradeInValuationView quote(TradeInQuoteRequest dto) {
        if (dto.imei() == null || dto.deviceRef() == null) {
            throw new BadRequestException("imei and deviceRef are required");
        }
        String tenant = tenantScope.currentTenantId();
        TradeInValuation v = new TradeInValuation();
        v.setId(UUID.randomUUID().toString());
        v.setTenantId(tenant);
        v.setHref(ApiConstants.BASE_PATH + "/tradeInValuation/" + v.getId());
        v.setPartyId(dto.relatedPartyId());
        partyScope.scopedPartyId().ifPresent(v::setPartyId);
        v.setImei(dto.imei().replaceAll("\\s", ""));
        v.setDeviceRef(dto.deviceRef());
        JsonNode answers = mapper.answers(dto.conditionAnswers());
        v.setConditionJson(mapper.writeJson(answers));
        boolean blacklisted = flags.existsByTenantIdAndImeiAndFlag(tenant, v.getImei(),
                DeviceFlag.BLACKLISTED);
        Estimate estimate = blacklisted
                ? new Estimate(BigDecimal.ZERO, "EUR", "IMEI is blacklisted — a flagged device is worth zero")
                : estimate(tenant, v.getDeviceRef(), answers);
        v.setEstimatedValue(estimate.value());
        v.setCurrency(estimate.currency());
        v.setOfferExpiry(OffsetDateTime.now().plusDays(offerDays));
        v.setChannel(dto.channel() == null ? "shop" : dto.channel());
        v.setPaymentRef(dto.paymentRef());
        v.setAgreementRef(dto.agreementRef());
        v.setStatus(TradeInValuation.QUOTED);
        v.setCreatedAt(OffsetDateTime.now());
        v.setLastUpdate(OffsetDateTime.now());
        valuations.save(v);
        TradeInValuationView view = view(v).withNote(estimate.note());
        events.publish("TradeInQuoted", "tradeInValuation", view);
        log.info("trade-in {} quoted {} {} for {} ({})", v.getId(), v.getEstimatedValue(),
                v.getCurrency(), v.getDeviceRef(), blacklisted ? "BLACKLISTED" : "clean");
        return view;
    }

    @Transactional
    public TradeInValuationView accept(String id) {
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
        TradeInValuationView view = view(v);
        events.publish("TradeInAccepted", "tradeInValuation", view);
        return view;
    }

    /** The device is on its way (drop-off scan / return-parcel event). */
    @Transactional
    public TradeInValuationView inTransit(String id) {
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
    public TradeInValuationView grade(String id, GradingRequest dto) {
        TradeInValuation v = own(id);
        if (!List.of(TradeInValuation.ACCEPTED, TradeInValuation.IN_TRANSIT).contains(v.getStatus())) {
            throw new ConflictException("grading needs an accepted/in-transit valuation (is "
                    + v.getStatus() + ")");
        }
        if (dto == null || dto.finalValue() == null) {
            throw new BadRequestException("finalValue is required");
        }
        BigDecimal finalValue = money(dto.finalValue());
        BigDecimal delta = finalValue.subtract(v.getEstimatedValue());
        GradingEvent g = new GradingEvent();
        g.setId(UUID.randomUUID().toString());
        g.setTenantId(v.getTenantId());
        g.setValuationRef(v.getId());
        g.setPartnerRef(dto.partnerRef() == null ? "in-house" : dto.partnerRef());
        g.setFinalGrade(dto.finalGrade());
        g.setFinalValue(finalValue);
        g.setDelta(delta);
        g.setNote(dto.note());
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
            TradeInValuationView view = view(v);
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
            events.publish("DeviceChargeRequestedEvent", "deviceChargeRequest",
                    DeviceChargeRequest.of(v.getId(), delta.negate(), v.getCurrency(), v.getPartyId()));
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
    public TradeInValuationView acceptRevaluation(String id) {
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
        TradeInValuationView view = view(v);
        events.publish("TradeInSettled", "tradeInValuation", view);
        return view;
    }

    /** The customer refuses the revised value — the device goes back. */
    @Transactional
    public TradeInValuationView rejectRevaluation(String id) {
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
    public List<TradeInValuationView> findAll(String relatedPartyId, String status) {
        String scoped = partyScope.scopedPartyId().orElse(relatedPartyId);
        return valuations.findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId()).stream()
                .filter(v -> scoped == null || scoped.equals(v.getPartyId()))
                .filter(v -> status == null || status.equals(v.getStatus()))
                .map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public TradeInValuationView findById(String id) {
        TradeInValuation v = own(id);
        return view(v).withGrading(gradings
                .findByTenantIdAndValuationRefOrderByCreatedAtAsc(v.getTenantId(), v.getId())
                .stream().map(mapper::view).toList());
    }

    /* ---------- the residual table (staff-curated) ---------- */

    @Transactional
    public TradeInResidualView upsertResidual(ResidualRequest dto) {
        if (dto.deviceRef() == null) {
            throw new BadRequestException("deviceRef is required");
        }
        if (dto.ageMonths() == null) {
            throw new BadRequestException("ageMonths is required");
        }
        if (dto.baseValue() == null) {
            throw new BadRequestException("baseValue is required");
        }
        String tenant = tenantScope.currentTenantId();
        String deviceRef = dto.deviceRef();
        int ageMonths = dto.ageMonths();
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
        row.setBaseValue(money(dto.baseValue()));
        row.setCurrency(dto.currency() == null ? "EUR" : dto.currency());
        row.setLastUpdate(OffsetDateTime.now());
        residuals.save(row);
        return mapper.view(row);
    }

    @Transactional(readOnly = true)
    public List<TradeInResidualView> residualTable(String deviceRef) {
        String tenant = tenantScope.currentTenantId();
        List<TradeInResidual> rows = deviceRef == null
                ? residuals.findByTenantIdOrderByDeviceRefAscAgeMonthsAsc(tenant)
                : residuals.findByTenantIdAndDeviceRefOrderByAgeMonthsAsc(tenant, deviceRef);
        return rows.stream().map(mapper::view).toList();
    }

    @Transactional
    public void deleteResidual(String id) {
        TradeInResidual row = residuals.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("TradeInResidual", id));
        residuals.delete(row);
    }

    /* ---------- the blacklist flag stub ---------- */

    @Transactional
    public DeviceFlagView flag(DeviceFlagRequest dto) {
        if (dto.imei() == null) {
            throw new BadRequestException("imei is required");
        }
        return flagImei(dto.imei().replaceAll("\\s", ""),
                dto.reason() == null ? "lost" : dto.reason(),
                dto.sourceRef() == null ? "manual" : dto.sourceRef());
    }

    /** Also the SIM-block listener's entry point (already tenant-contexted). */
    @Transactional
    public DeviceFlagView flagImei(String imei, String reason, String sourceRef) {
        String tenant = tenantScope.currentTenantId();
        if (flags.existsByTenantIdAndImeiAndFlag(tenant, imei, DeviceFlag.BLACKLISTED)) {
            return mapper.view(flags.findByTenantIdAndImei(tenant, imei).get(0));   // idempotent
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
        DeviceFlagView view = mapper.view(f);
        // the registry push (EIR/GSMA) is an adapter someone deploys; the
        // EVENT is the seam — mock deployments stop here, honestly
        events.publish("DeviceBlacklistRequested", "deviceFlag", view);
        log.info("device flag: IMEI {} blacklisted ({}, {})", imei, reason, sourceRef);
        return view;
    }

    @Transactional(readOnly = true)
    public List<DeviceFlagView> flagsOf(String imei) {
        String tenant = tenantScope.currentTenantId();
        List<DeviceFlag> rows = imei == null
                ? flags.findByTenantIdOrderByCreatedAtDesc(tenant)
                : flags.findByTenantIdAndImei(tenant, imei);
        return rows.stream().map(mapper::view).toList();
    }

    /* ---------- internals ---------- */

    private record Estimate(BigDecimal value, String currency, String note) {
    }

    /** Base value = the residual row at the device's age (nearest not-younger
     * row wins); each declared defect takes its published haircut. */
    private Estimate estimate(String tenant, String deviceRef, JsonNode answers) {
        List<TradeInResidual> rows = residuals.findByTenantIdAndDeviceRefOrderByAgeMonthsAsc(tenant, deviceRef);
        if (rows.isEmpty()) {
            return new Estimate(BigDecimal.ZERO, "EUR",
                    "no residual row for '" + deviceRef + "' — staff curate the table");
        }
        int age = answers.hasNonNull("ageMonths") ? Integer.parseInt(answers.get("ageMonths").asText()) : 0;
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
            if (Boolean.parseBoolean(answers.path(defect.getKey()).asText())) {
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
        TradeInValuationView view = view(v);
        if (refundRef == null) {
            view = view.withNote("no reachable payment to refund against — delta stays owed to the customer");
        }
        events.publish("TradeInSettled", "tradeInValuation", view);
    }

    private Optional<DeviceAgreement> linkedAgreement(TradeInValuation v) {
        if (v.getAgreementRef() == null) {
            return Optional.empty();
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

    private TradeInValuationView view(TradeInValuation v) {
        return mapper.view(v);
    }

    /** Money as the entity stores it: two decimals, half up. */
    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
