package com.bss.userroles.privacy;

import com.bss.userroles.dto.EraseReceipt;
import com.bss.userroles.dto.EraseRequest;
import com.bss.userroles.exception.BadRequestException;
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
    public EraseReceipt erase(@RequestBody EraseRequest request) {
        if (!isDpo()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND); // 404, never 403
        }
        if (request.partyId() == null || request.partyId().isBlank()) {
            throw new BadRequestException("partyId is required");
        }
        idp.eraseUser(tenantScope.currentTenantId(), request.partyId());
        return new EraseReceipt("identity", 1, 0,
                "login disabled, IdP profile scrubbed; account id kept as the audit's reference");
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
