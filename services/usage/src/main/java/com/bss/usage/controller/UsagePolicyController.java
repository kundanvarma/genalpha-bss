package com.bss.usage.controller;

import com.bss.usage.api.ApiConstants;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

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
    public ResponseEntity<Map<String, Object>> createPool(@RequestBody Map<String, Object> dto) {
        Map<String, Object> created = pools.create(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping(ApiConstants.BASE_PATH + "/allowancePool")
    public ResponseEntity<List<Map<String, Object>>> listPools() {
        return ResponseEntity.ok(pools.list());
    }

    @GetMapping(ApiConstants.BASE_PATH + "/allowancePool/{id}")
    public ResponseEntity<Map<String, Object>> getPool(@PathVariable("id") String id) {
        return ResponseEntity.ok(pools.get(id));
    }

    @PostMapping(ApiConstants.BASE_PATH + "/allowancePool/{id}/member")
    public ResponseEntity<Map<String, Object>> addMember(@PathVariable("id") String id,
            @RequestBody Map<String, Object> dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(pools.addMember(id, dto));
    }

    @PatchMapping(ApiConstants.BASE_PATH + "/allowancePool/{id}/member/{partyId}")
    public ResponseEntity<Map<String, Object>> patchMember(@PathVariable("id") String id,
            @PathVariable("partyId") String partyId, @RequestBody Map<String, Object> dto) {
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
    public ResponseEntity<List<Map<String, Object>>> spendPolicy(
            @RequestParam(name = "partyId", required = false) String partyId) {
        return ResponseEntity.ok(spendPolicy.policyOf(spendPolicy.resolveParty(partyId)));
    }

    @PatchMapping(ApiConstants.BASE_PATH + "/spendPolicy/{meterType}")
    public ResponseEntity<Map<String, Object>> patchSpendPolicy(
            @PathVariable("meterType") String meterType,
            @RequestParam(name = "partyId", required = false) String partyId,
            @RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(spendPolicy.patch(spendPolicy.resolveParty(partyId), meterType, dto));
    }

    /** The audited "keep me roaming" election (EU 2022/612: past the limit
     * only on the customer's explicit request). */
    @PostMapping(ApiConstants.BASE_PATH + "/roamingLimit/continue")
    public ResponseEntity<Map<String, Object>> roamingContinue(
            @RequestBody(required = false) Map<String, Object> dto) {
        String partyId = dto == null || dto.get("partyId") == null
                ? null : String.valueOf(dto.get("partyId"));
        return ResponseEntity.ok(spendPolicy.roamingContinue(spendPolicy.resolveParty(partyId)));
    }

    /** Machine seam: one rated charge lands on the party's meters; the answer
     * says whether the charging edge may let it stand. */
    @PostMapping(ApiConstants.BASE_PATH + "/spendMeter/charge")
    public ResponseEntity<Map<String, Object>> charge(@RequestBody Map<String, Object> dto) {
        if (dto.get("partyId") == null) {
            throw new BadRequestException("partyId is required");
        }
        if (!(dto.get("amount") instanceof Map<?, ?> money) || money.get("value") == null) {
            throw new BadRequestException("amount {value, unit} is required");
        }
        return ResponseEntity.ok(usage.notifySpendThreshold(Map.of(
                "partyId", dto.get("partyId"),
                "chargeClass", dto.get("chargeClass") == null ? "usage" : dto.get("chargeClass"),
                "amount", Map.of("value", new BigDecimal(String.valueOf(money.get("value"))),
                        "unit", money.get("unit") == null ? "" : money.get("unit")))));
    }

    // ---- auto top-up ----

    @GetMapping(ApiConstants.BASE_PATH + "/autoTopupPolicy")
    public ResponseEntity<Map<String, Object>> getAutoTopup(
            @RequestParam(name = "partyId", required = false) String partyId) {
        return ResponseEntity.ok(autoTopup.get(partyId));
    }

    @PutMapping(ApiConstants.BASE_PATH + "/autoTopupPolicy")
    public ResponseEntity<Map<String, Object>> putAutoTopup(
            @RequestParam(name = "partyId", required = false) String partyId,
            @RequestBody Map<String, Object> dto) {
        return ResponseEntity.ok(autoTopup.put(partyId, dto));
    }

    // ---- travel pass (machine/back-office grant) ----

    @PostMapping(ApiConstants.BASE_PATH + "/travelPass")
    public ResponseEntity<Map<String, Object>> travelPass(@RequestBody Map<String, Object> dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(usage.createTravelPass(dto));
    }
}
