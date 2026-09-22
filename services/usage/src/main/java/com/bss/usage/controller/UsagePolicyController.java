package com.bss.usage.controller;

import com.bss.usage.api.ApiConstants;
import com.bss.usage.dto.AutoTopupPolicyRequest;
import com.bss.usage.dto.AutoTopupPolicyView;
import com.bss.usage.dto.Money;
import com.bss.usage.dto.PoolMemberPatch;
import com.bss.usage.dto.PoolMemberRequest;
import com.bss.usage.dto.PoolRequest;
import com.bss.usage.dto.PoolView;
import com.bss.usage.dto.RoamingContinueRequest;
import com.bss.usage.dto.SpendChargeRequest;
import com.bss.usage.dto.SpendMeterView;
import com.bss.usage.dto.SpendPolicyPatch;
import com.bss.usage.dto.SpendThresholdNotification;
import com.bss.usage.dto.SpendVerdict;
import com.bss.usage.dto.TravelPassRequest;
import com.bss.usage.dto.TravelPassView;
import com.bss.usage.exception.BadRequestException;
import com.bss.usage.service.AutoTopupService;
import com.bss.usage.service.PoolService;
import com.bss.usage.service.SpendPolicyService;
import com.bss.usage.service.UsageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The usage-policy faces: the household pool (owner-managed), the three
 * spend-meter faces (spend cap / content-services cap+barring / roaming
 * limit), auto top-up consent, and travel passes. Customer endpoints are
 * party-scoped; /spendMeter/charge and /travelPass are machine seams.
 */
@RestController
public class UsagePolicyController {

    private final PoolService pools;
    private final SpendPolicyService spendPolicy;
    private final AutoTopupService autoTopup;
    private final UsageService usage;

    public UsagePolicyController(PoolService pools, SpendPolicyService spendPolicy,
            AutoTopupService autoTopup, UsageService usage) {
        this.pools = pools;
        this.spendPolicy = spendPolicy;
        this.autoTopup = autoTopup;
        this.usage = usage;
    }

    // ---- household pool ----

    @PostMapping(ApiConstants.BASE_PATH + "/allowancePool")
    public ResponseEntity<PoolView> createPool(@RequestBody PoolRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(pools.create(dto));
    }

    @GetMapping(ApiConstants.BASE_PATH + "/allowancePool")
    public ResponseEntity<List<PoolView>> listPools() {
        return ResponseEntity.ok(pools.list());
    }

    @GetMapping(ApiConstants.BASE_PATH + "/allowancePool/{id}")
    public ResponseEntity<PoolView> getPool(@PathVariable("id") String id) {
        return ResponseEntity.ok(pools.get(id));
    }

    @PostMapping(ApiConstants.BASE_PATH + "/allowancePool/{id}/member")
    public ResponseEntity<PoolView> addMember(@PathVariable("id") String id, @RequestBody PoolMemberRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(pools.addMember(id, dto));
    }

    @PatchMapping(ApiConstants.BASE_PATH + "/allowancePool/{id}/member/{partyId}")
    public ResponseEntity<PoolView> patchMember(@PathVariable("id") String id,
            @PathVariable("partyId") String partyId, @RequestBody PoolMemberPatch dto) {
        return ResponseEntity.ok(pools.patchMember(id, partyId, dto));
    }

    @DeleteMapping(ApiConstants.BASE_PATH + "/allowancePool/{id}/member/{partyId}")
    public ResponseEntity<Void> removeMember(@PathVariable("id") String id,
            @PathVariable("partyId") String partyId) {
        pools.removeMember(id, partyId);
        return ResponseEntity.noContent().build();
    }

    // ---- spend meters (spend / content / roaming) ----

    @GetMapping(ApiConstants.BASE_PATH + "/spendPolicy")
    public ResponseEntity<List<SpendMeterView>> spendPolicy(
            @RequestParam(name = "partyId", required = false) String partyId) {
        return ResponseEntity.ok(spendPolicy.policyOf(spendPolicy.resolveParty(partyId)));
    }

    @PatchMapping(ApiConstants.BASE_PATH + "/spendPolicy/{meterType}")
    public ResponseEntity<SpendMeterView> patchSpendPolicy(
            @PathVariable("meterType") String meterType,
            @RequestParam(name = "partyId", required = false) String partyId,
            @RequestBody SpendPolicyPatch dto) {
        return ResponseEntity.ok(spendPolicy.patch(spendPolicy.resolveParty(partyId), meterType, dto));
    }

    /** The audited "keep me roaming" election (EU 2022/612: past the limit
     * only on the customer's explicit request). */
    @PostMapping(ApiConstants.BASE_PATH + "/roamingLimit/continue")
    public ResponseEntity<SpendMeterView> roamingContinue(
            @RequestBody(required = false) RoamingContinueRequest dto) {
        String partyId = dto == null ? null : dto.partyId();
        return ResponseEntity.ok(spendPolicy.roamingContinue(spendPolicy.resolveParty(partyId)));
    }

    /** Machine seam: one rated charge lands on the party's meters; the answer
     * says whether the charging edge may let it stand. The tenant is the
     * caller's — the body never names one. */
    @PostMapping(ApiConstants.BASE_PATH + "/spendMeter/charge")
    public ResponseEntity<SpendVerdict> charge(@RequestBody SpendChargeRequest dto) {
        if (dto.partyId() == null) {
            throw new BadRequestException("partyId is required");
        }
        if (dto.amount() == null || dto.amount().value() == null) {
            throw new BadRequestException("amount {value, unit} is required");
        }
        return ResponseEntity.ok(usage.notifySpendThreshold(new SpendThresholdNotification(null, dto.partyId(),
                dto.chargeClass() == null ? "usage" : dto.chargeClass(),
                new Money(dto.amount().value(), dto.amount().unit() == null ? "" : dto.amount().unit()))));
    }

    // ---- auto top-up ----

    @GetMapping(ApiConstants.BASE_PATH + "/autoTopupPolicy")
    public ResponseEntity<AutoTopupPolicyView> getAutoTopup(
            @RequestParam(name = "partyId", required = false) String partyId) {
        return ResponseEntity.ok(autoTopup.get(partyId));
    }

    @PutMapping(ApiConstants.BASE_PATH + "/autoTopupPolicy")
    public ResponseEntity<AutoTopupPolicyView> putAutoTopup(
            @RequestParam(name = "partyId", required = false) String partyId,
            @RequestBody AutoTopupPolicyRequest dto) {
        return ResponseEntity.ok(autoTopup.put(partyId, dto));
    }

    // ---- travel pass (machine/back-office grant) ----

    @PostMapping(ApiConstants.BASE_PATH + "/travelPass")
    public ResponseEntity<TravelPassView> travelPass(@RequestBody TravelPassRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(usage.createTravelPass(dto));
    }
}
