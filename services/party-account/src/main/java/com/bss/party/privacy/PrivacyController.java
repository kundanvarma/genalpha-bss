package com.bss.party.privacy;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * The GDPR front door (routed via the gateway as /privacy/v1/**):
 * EXPORT for anyone about themselves — their own token does the reading
 * everywhere; the DPO (roles:admin) may name a party. ERASE and the
 * audit trail are DPO-only.
 */
@RestController
@RequestMapping("/privacy/v1")
public class PrivacyController {

    private final PrivacyService privacy;

    public PrivacyController(PrivacyService privacy) {
        this.privacy = privacy;
    }

    @GetMapping("/export")
    public PrivacyPassport export(@RequestParam(required = false) String partyId,
            @RequestHeader("Authorization") String bearer) {
        String subject = subject();
        String target = partyId == null || partyId.isBlank() ? subject : partyId;
        if (!target.equals(subject) && !isDpo()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND); // 404, never 403
        }
        return privacy.export(target, bearer);
    }

    @PostMapping("/erase")
    public ErasureReport erase(@RequestBody EraseRequest request,
            @RequestHeader("Authorization") String bearer) {
        if (!isDpo()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String target = String.valueOf(request.partyId());
        return privacy.erase(target, bearer, subject());
    }

    @GetMapping("/erasure")
    public List<ErasureAuditRow> auditTrail() {
        if (!isDpo()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return privacy.auditTrail();
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
