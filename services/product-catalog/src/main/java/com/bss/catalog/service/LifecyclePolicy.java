package com.bss.catalog.service;

import com.bss.catalog.dto.ProductOfferingDto;
import com.bss.catalog.entity.ProductOffering;
import com.bss.catalog.exception.BadRequestException;
import com.bss.catalog.security.TenantRegistry;
import com.bss.catalog.security.TenantScope;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * L1 — the lifecycle's TEETH, server-side. The deep pass found that draft
 * invisibility was client-side courtesy (a guest omitting the filter read
 * drafts) and that ordering a draft returned 201. This component is the
 * single answer: who may SEE what, which transitions are legal, and what
 * counts as sellable — regardless of what any client asks for.
 */
@Component
public class LifecyclePolicy {

    /** The TMF620 ladder, in rank order. 'Active' is the platform's
     *  historical alias for Launched and ranks with it. */
    public static final List<String> LADDER =
            List.of("In study", "In design", "In test", "Launched", "Retired", "Obsolete");

    private final TenantRegistry tenants;
    private final TenantScope tenantScope;

    public LifecyclePolicy(TenantRegistry tenants, TenantScope tenantScope) {
        this.tenants = tenants;
        this.tenantScope = tenantScope;
    }

    /** Staff (or machine) callers carry catalog authority; guests and
     *  customers do not — and only staff may see the unlaunched shelf. */
    public boolean staffCaller() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities() != null && auth.getAuthorities().stream()
                .anyMatch(a -> "catalog:read".equals(a.getAuthority())
                        || "catalog:write".equals(a.getAuthority()));
    }

    /** Launched/Active AND inside its validFor window — the ONLY thing a
     *  non-staff caller ever sees, whatever their query says. */
    public boolean sellable(ProductOffering entity) {
        return launched(entity.getLifecycleStatus())
                && inWindow(entity.getValidFrom(), entity.getValidTo());
    }

    public boolean sellableDto(ProductOfferingDto dto) {
        OffsetDateTime from = null;
        OffsetDateTime to = null;
        Map<String, String> window = dto.getValidFor();
        if (window != null) {
            from = window.get("startDateTime") == null ? null
                    : OffsetDateTime.parse(window.get("startDateTime"));
            to = window.get("endDateTime") == null ? null
                    : OffsetDateTime.parse(window.get("endDateTime"));
        }
        return launched(dto.getLifecycleStatus()) && inWindow(from, to);
    }

    public static boolean launched(String status) {
        return "Active".equals(status) || "Launched".equals(status);
    }

    public static boolean inWindow(OffsetDateTime from, OffsetDateTime to) {
        OffsetDateTime now = OffsetDateTime.now();
        return (from == null || !from.isAfter(now)) && (to == null || to.isAfter(now));
    }

    /** The tenant's governance: 'direct' (create is live — the default) or
     *  'governed' (create lands In design; the ladder is enforced). */
    public boolean governed() {
        TenantRegistry.TenantEntry entry = tenants.byId(tenantScope.currentTenantId());
        return entry != null && "governed".equalsIgnoreCase(entry.getCatalogGovernance());
    }

    /** Governed-mode transition rule: one rung at a time going up, free
     *  movement among the pre-launch states, Retired only from Launched,
     *  Obsolete only from Retired. Direct mode: anything goes (today). */
    public void requireLegalTransition(String from, String to) {
        if (!governed() || from == null || from.equals(to)) {
            return;
        }
        int a = rank(from);
        int b = rank(to);
        if (a < 0 || b < 0) {
            throw new BadRequestException("unknown lifecycle state '" + (a < 0 ? from : to)
                    + "' — the ladder is " + LADDER);
        }
        boolean preLaunchShuffle = a <= 2 && b <= 2;
        boolean oneRungUp = b == a + 1;
        if (!(preLaunchShuffle || oneRungUp)) {
            throw new BadRequestException("illegal lifecycle transition '" + from + "' -> '"
                    + to + "' — governed catalogs move one rung at a time (" + LADDER + ")");
        }
    }

    private static int rank(String status) {
        String normalized = "Active".equals(status) ? "Launched" : status;
        return LADDER.indexOf(normalized);
    }
}
