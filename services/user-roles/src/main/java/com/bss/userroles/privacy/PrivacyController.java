package com.bss.userroles.privacy;

import com.bss.userroles.security.TenantScope;
import com.bss.userroles.service.IdpAdminClient;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * The GDPR corner of identity: erasure DISABLES the login and scrubs
 * the IdP profile — the person can no longer authenticate anywhere,
 * and their name and email leave the realm. The account id survives as
 * the audit's party reference; ids are pseudonyms, not personal data
 * once everything they pointed at is gone.
 */
@RestController
@RequestMapping("/privacy/v1")
public class PrivacyController {

    private final IdpAdminClient idp;
    private final TenantScope tenantScope;

    public PrivacyController(IdpAdminClient idp, TenantScope tenantScope) {
        this.idp = idp;
        this.tenantScope = tenantScope;
    }

    @PostMapping("/erase")
    public Map<String, Object> erase(@RequestBody Map<String, Object> request) {
        if (!isDpo()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND); // 404, never 403
        }
        String target = String.valueOf(request.get("partyId"));
        idp.eraseUser(tenantScope.currentTenantId(), target);
        return Map.of("category", "identity", "deleted", 1, "retained", 0,
                "note", "login disabled, IdP profile scrubbed; account id kept as the audit's reference");
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
