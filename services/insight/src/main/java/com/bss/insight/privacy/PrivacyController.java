package com.bss.insight.privacy;

import com.bss.insight.repository.CustomerSignalRepository;
import com.bss.insight.repository.TwinVaultRepository;
import com.bss.insight.repository.VisitorEventRepository;
import com.bss.insight.repository.VisitorProfileRepository;
import com.bss.insight.security.TenantScope;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * The GDPR corner of the behavioural store: visitor profiles stitched
 * to this person, and every event under those visitor ids. ERASE (DPO
 * only) takes the events WITH the profiles — behavioural data has no
 * legal hold, it simply goes.
 */
@RestController
@RequestMapping("/privacy/v1")
public class PrivacyController {

    private final VisitorProfileRepository profiles;
    private final VisitorEventRepository events;
    private final CustomerSignalRepository signals;
    private final TwinVaultRepository twinVault;
    private final TenantScope tenantScope;

    public PrivacyController(VisitorProfileRepository profiles,
            VisitorEventRepository events, CustomerSignalRepository signals,
            TwinVaultRepository twinVault, TenantScope tenantScope) {
        this.profiles = profiles;
        this.events = events;
        this.signals = signals;
        this.twinVault = twinVault;
        this.tenantScope = tenantScope;
    }

    @GetMapping("/export")
    public Map<String, Object> export(@RequestParam(required = false) String partyId) {
        String subject = subject();
        String target = partyId == null || partyId.isBlank() ? subject : partyId;
        if (!target.equals(subject) && !isDpo()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND); // 404, never 403
        }
        var stitched = profiles.findByTenantIdAndPartyId(tenantScope.currentTenantId(), target);
        var said = signals.findByTenantIdAndPartyId(tenantScope.currentTenantId(), target);
        return Map.of("category", "behavioral", "count", stitched.size(), "items", stitched,
                "signals", said.size());
    }

    @PostMapping("/erase")
    @Transactional
    public Map<String, Object> erase(@RequestBody Map<String, Object> request) {
        if (!isDpo()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String target = String.valueOf(request.get("partyId"));
        String tenant = tenantScope.currentTenantId();
        var stitched = profiles.findByTenantIdAndPartyId(tenant, target);
        for (var profile : stitched) {
            events.deleteByTenantIdAndVisitorId(tenant, profile.getVisitorId());
        }
        profiles.deleteAll(stitched);
        // the signal store is pseudonymized, not anonymized — it goes too (SI-P1)
        long signalsGone = signals.deleteByTenantIdAndPartyId(tenant, target);
        // Tvilling: destroying the vault keys makes any twin that ever left
        // permanently unlinkable — erasure reaches the cloud retroactively
        long twinKeysGone = twinVault.deleteByTenantIdAndPartyId(tenant, target);
        return Map.of("category", "behavioral", "deleted", stitched.size(),
                "signalsDeleted", signalsGone, "twinKeysDestroyed", twinKeysGone, "retained", 0);
    }

    private String subject() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "" : auth.getName();
    }

    private boolean isDpo() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if ("roles:admin".equals(ga.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
