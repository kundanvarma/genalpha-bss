package com.bss.intelligence.llm;

import com.bss.intelligence.security.TenantRegistry;
import com.bss.intelligence.security.TenantScope;
import org.springframework.stereotype.Component;

/**
 * The operator speaks its market's language. Any copilot that drafts
 * CUSTOMER-FACING copy (rail captions, banners, offering names, journey
 * messages) appends this instruction so a Norwegian shop is never
 * personalized in English. English tenants pay zero prompt cost.
 */
@Component
public class TenantVoice {

    private final TenantScope tenantScope;
    private final TenantRegistry tenants;

    public TenantVoice(TenantScope tenantScope, TenantRegistry tenants) {
        this.tenantScope = tenantScope;
        this.tenants = tenants;
    }

    /** A system-prompt suffix ("" for English/unknown locales). */
    public String instruction() {
        TenantRegistry.TenantEntry entry = tenants.byId(tenantScope.currentTenantId());
        String locale = entry == null ? null : entry.getLocale();
        if (locale == null || locale.isBlank() || "en".equalsIgnoreCase(locale)) {
            return "";
        }
        return "\nThe operator's storefront language is '" + locale + "'. Write ALL "
                + "customer-facing copy — captions, banner text, offering names, "
                + "descriptions, messages — in that language. Labels, JSON keys and "
                + "enum values stay in English.";
    }
}
