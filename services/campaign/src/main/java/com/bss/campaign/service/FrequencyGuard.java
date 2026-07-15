package com.bss.campaign.service;

import com.bss.campaign.entity.MarketingTouch;
import com.bss.campaign.entity.MartechSetting;
import com.bss.campaign.exception.BadRequestException;
import com.bss.campaign.repository.MarketingTouchRepository;
import com.bss.campaign.repository.MartechSettingRepository;
import com.bss.campaign.security.TenantScope;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The tenant's marketing-touch budget, enforced in one place. Campaigns
 * ask before ledgering a customer; journeys ask before a message step
 * (and postpone instead of dropping). Every ACTUAL send — campaign or
 * journey, never holdouts, never transactional notifications — records a
 * touch. Braze's retrospective said it plainly: optional guardrails get
 * forgotten; this one is in the model.
 */
@Component
public class FrequencyGuard {

    private final MartechSettingRepository settings;
    private final MarketingTouchRepository touches;
    private final TenantScope tenantScope;

    public FrequencyGuard(MartechSettingRepository settings, MarketingTouchRepository touches,
            TenantScope tenantScope) {
        this.settings = settings;
        this.touches = touches;
        this.tenantScope = tenantScope;
    }

    /** May marketing speak to this customer right now? */
    @Transactional(readOnly = true)
    public boolean canSend(String partyId) {
        String tenant = tenantScope.currentTenantId();
        MartechSetting setting = settings.findById(tenant).orElse(null);
        if (setting == null || setting.getMaxMarketingMessages() <= 0) {
            return true; // no budget configured — the cap is off
        }
        OffsetDateTime windowStart = OffsetDateTime.now().minusDays(Math.max(setting.getPerDays(), 1));
        return touches.countByTenantIdAndPartyIdAndSentAtAfter(tenant, partyId, windowStart)
                < setting.getMaxMarketingMessages();
    }

    /**
     * QUIET HOURS: when are we inside the tenant's silence window — and
     * until when? Empty = free to speak. The window is tenant-local and
     * may wrap midnight (21:00 -> 08:00).
     */
    @Transactional(readOnly = true)
    public java.util.Optional<OffsetDateTime> quietUntil() {
        MartechSetting setting = settings.findById(tenantScope.currentTenantId()).orElse(null);
        if (setting == null || setting.getQuietStart() == null || setting.getQuietEnd() == null
                || setting.getQuietStart().isBlank() || setting.getQuietEnd().isBlank()) {
            return java.util.Optional.empty();
        }
        java.time.LocalTime start;
        java.time.LocalTime end;
        java.time.ZoneId zone;
        try {
            start = java.time.LocalTime.parse(setting.getQuietStart());
            end = java.time.LocalTime.parse(setting.getQuietEnd());
            zone = setting.getTimeZone() == null || setting.getTimeZone().isBlank()
                    ? java.time.ZoneId.systemDefault() : java.time.ZoneId.of(setting.getTimeZone());
        } catch (Exception e) {
            return java.util.Optional.empty(); // unreadable window = no window
        }
        if (start.equals(end)) {
            return java.util.Optional.empty();
        }
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(zone);
        java.time.LocalTime t = now.toLocalTime();
        boolean wraps = end.isBefore(start);
        boolean inside = wraps ? !t.isBefore(start) || t.isBefore(end)
                : !t.isBefore(start) && t.isBefore(end);
        if (!inside) {
            return java.util.Optional.empty();
        }
        java.time.ZonedDateTime until = now.with(end);
        if (!until.isAfter(now)) {
            until = until.plusDays(1);
        }
        return java.util.Optional.of(until.toOffsetDateTime());
    }

    /** A marketing message actually went out — spend one touch. */
    @Transactional
    public void record(String partyId, String source) {
        MarketingTouch touch = new MarketingTouch();
        touch.setId(UUID.randomUUID().toString());
        touch.setTenantId(tenantScope.currentTenantId());
        touch.setPartyId(partyId);
        touch.setSource(source);
        touch.setSentAt(OffsetDateTime.now());
        touches.save(touch);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> settingsOf() {
        MartechSetting setting = settings.findById(tenantScope.currentTenantId()).orElse(null);
        return toMap(setting);
    }

    /** Upsert — the settings row IS the tenant's guardrail. */
    @Transactional
    public Map<String, Object> save(Map<String, Object> dto) {
        int max = dto.get("maxMarketingMessages") == null ? 0
                : Integer.parseInt(String.valueOf(dto.get("maxMarketingMessages")));
        int days = dto.get("perDays") == null ? 1
                : Integer.parseInt(String.valueOf(dto.get("perDays")));
        if (max < 0 || days < 1) {
            throw new BadRequestException("maxMarketingMessages must be >= 0 (0 = off), perDays >= 1");
        }
        String quietStart = str(dto.get("quietStart"));
        String quietEnd = str(dto.get("quietEnd"));
        String zone = str(dto.get("timeZone"));
        if ((quietStart == null) != (quietEnd == null)) {
            throw new BadRequestException("quietStart and quietEnd come as a pair (HH:mm)");
        }
        if (quietStart != null) {
            try {
                java.time.LocalTime.parse(quietStart);
                java.time.LocalTime.parse(quietEnd);
                if (zone != null) {
                    java.time.ZoneId.of(zone);
                }
            } catch (Exception e) {
                throw new BadRequestException("quiet hours must be HH:mm and timeZone a valid zone id");
            }
        }
        MartechSetting setting = settings.findById(tenantScope.currentTenantId())
                .orElseGet(MartechSetting::new);
        setting.setTenantId(tenantScope.currentTenantId());
        setting.setMaxMarketingMessages(max);
        setting.setPerDays(days);
        // full-replace semantics: a save without quiet hours clears them
        setting.setQuietStart(quietStart);
        setting.setQuietEnd(quietEnd);
        setting.setTimeZone(zone);
        setting.setLastUpdate(OffsetDateTime.now());
        return toMap(settings.save(setting));
    }

    private String str(Object v) {
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v);
    }

    private Map<String, Object> toMap(MartechSetting setting) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("maxMarketingMessages", setting == null ? 0 : setting.getMaxMarketingMessages());
        map.put("perDays", setting == null ? 1 : setting.getPerDays());
        map.put("capActive", setting != null && setting.getMaxMarketingMessages() > 0);
        if (setting != null && setting.getQuietStart() != null) {
            map.put("quietStart", setting.getQuietStart());
            map.put("quietEnd", setting.getQuietEnd());
            if (setting.getTimeZone() != null) {
                map.put("timeZone", setting.getTimeZone());
            }
        }
        map.put("quietActive", setting != null && setting.getQuietStart() != null);
        map.put("@type", "MartechSetting");
        return map;
    }
}
