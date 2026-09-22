package com.bss.cart.privacy;

import com.bss.cart.dto.EraseReceipt;
import com.bss.cart.dto.EraseRequest;
import com.bss.cart.dto.PrivacyExport;
import com.bss.cart.entity.ShoppingCart;
import com.bss.cart.exception.BadRequestException;
import com.bss.cart.repository.ShoppingCartRepository;
import com.bss.cart.security.TenantScope;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * The GDPR corner of this service. EXPORT rides the caller's OWN token —
 * a person exports what their credentials could already read; only the
 * DPO (roles:admin) may name another party. ERASE is DPO-only and
 * answers with honest counts: what this service deleted, nothing more.
 */
@RestController
@RequestMapping("/privacy/v1")
public class PrivacyController {

    private static final String CATEGORY = "carts";

    private final ShoppingCartRepository repository;
    private final TenantScope tenantScope;

    public PrivacyController(ShoppingCartRepository repository, TenantScope tenantScope) {
        this.repository = repository;
        this.tenantScope = tenantScope;
    }

    @GetMapping("/export")
    public PrivacyExport export(@RequestParam(required = false) String partyId) {
        String subject = subject();
        String target = partyId == null || partyId.isBlank() ? subject : partyId;
        if (!target.equals(subject) && !isDpo()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND); // 404, never 403
        }
        List<ShoppingCart> items = repository.findByTenantIdAndOwnerPartyId(tenantScope.currentTenantId(), target);
        return new PrivacyExport(CATEGORY, items.size(), items);
    }

    @PostMapping("/erase")
    public EraseReceipt erase(@RequestBody EraseRequest request) {
        if (!isDpo()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        // a nameless erasure must never reach the guest carts (owner null)
        if (request.partyId() == null || request.partyId().isBlank()) {
            throw new BadRequestException("partyId is required");
        }
        List<ShoppingCart> rows = repository.findByTenantIdAndOwnerPartyId(tenantScope.currentTenantId(),
                request.partyId());
        repository.deleteAll(rows);
        return new EraseReceipt(CATEGORY, rows.size(), 0);
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
