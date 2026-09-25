package com.bss.revenue.service;

import com.bss.revenue.dto.ChangeFinding;
import com.bss.revenue.dto.ConfigChangeRequest;
import com.bss.revenue.dto.ConfigChangeView;
import com.bss.revenue.entity.AccountMapping;
import com.bss.revenue.entity.FinancialConfigChange;
import com.bss.revenue.events.DomainEventPublisher;
import com.bss.revenue.exception.BadRequestException;
import com.bss.revenue.exception.ConflictException;
import com.bss.revenue.exception.NotFoundException;
import com.bss.revenue.repository.AccountMappingRepository;
import com.bss.revenue.repository.FinancialConfigChangeRepository;
import com.bss.revenue.security.TenantScope;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The ladder live financial configuration climbs: draft, validate, approve,
 * activate. These settings decide which account real money lands in, so the
 * service — not the screen — decides what may move and when.
 *
 * <p>Each rung checks the one below it, and activation re-runs validation
 * against the live row: an approval granted yesterday cannot activate a change
 * that became dangerous overnight, and a stale draft cannot silently overwrite
 * a colleague's edit.
 */
@Service
public class ConfigChangeService {

    private static final List<String> OPEN = List.of(
            FinancialConfigChange.DRAFT, FinancialConfigChange.VALIDATED, FinancialConfigChange.APPROVED);

    private final FinancialConfigChangeRepository changes;
    private final AccountMappingRepository mappings;
    private final RevenueService revenue;
    private final ChartGuard guard;
    private final TenantScope tenantScope;
    private final DomainEventPublisher events;

    public ConfigChangeService(FinancialConfigChangeRepository changes, AccountMappingRepository mappings,
            RevenueService revenue, ChartGuard guard, TenantScope tenantScope, DomainEventPublisher events) {
        this.changes = changes;
        this.mappings = mappings;
        this.revenue = revenue;
        this.guard = guard;
        this.tenantScope = tenantScope;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<ConfigChangeView> list(String state) {
        String tenant = tenantScope.currentTenantId();
        List<FinancialConfigChange> rows = state == null || state.isBlank()
                ? changes.findAllByTenantIdOrderByDraftedAtDesc(tenant)
                : changes.findAllByTenantIdAndStateOrderByDraftedAtDesc(tenant, state);
        List<ConfigChangeView> out = new ArrayList<>();
        for (FinancialConfigChange row : rows) {
            out.add(view(row));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public ConfigChangeView one(String id) {
        return view(load(id));
    }

    /** Rung one: write the proposal down, with what the account says today beside it. */
    @Transactional
    public ConfigChangeView draft(ConfigChangeRequest dto) {
        String tenant = tenantScope.currentTenantId();
        String key = dto.postingKey();
        if (key == null || !RevenueService.knownPostingKey(key)) {
            throw new BadRequestException("unknown posting key '" + key
                    + "' — one of " + RevenueService.postingKeys());
        }
        revenue.chart(); // the default chart is seeded lazily; a draft needs the live row
        AccountMapping live = mappings.findByTenantIdAndMappingKey(tenant, key).orElseThrow();
        if (!changes.findAllByTenantIdAndPostingKeyAndStateIn(tenant, key, OPEN).isEmpty()) {
            throw new ConflictException("a change to this account is already on the ladder —"
                    + " finish or withdraw it before proposing another");
        }
        FinancialConfigChange row = new FinancialConfigChange();
        row.setId(UUID.randomUUID().toString());
        row.setTenantId(tenant);
        row.setPostingKey(key);
        row.setCurrentCode(live.getAccountCode());
        row.setCurrentName(live.getAccountName());
        row.setCurrentValue(live.getConfigValue());
        row.setProposedCode(blankToNull(dto.accountCode()) == null ? live.getAccountCode() : dto.accountCode().trim());
        row.setProposedName(blankToNull(dto.accountName()) == null ? live.getAccountName() : dto.accountName().trim());
        row.setProposedValue(proposedValue(dto, live));
        row.setReason(blankToNull(dto.reason()));
        row.setState(FinancialConfigChange.DRAFT);
        row.setPostingsUsing(guard.postingsUsing(tenant, live.getAccountCode()));
        row.setDraftedBy(actor());
        row.setDraftedAt(OffsetDateTime.now());
        changes.save(row);
        return view(row);
    }

    /** Rung two: say out loud what this would do, and refuse what it must not do. */
    @Transactional
    public ConfigChangeView validate(String id) {
        FinancialConfigChange row = load(id);
        if (!FinancialConfigChange.DRAFT.equals(row.getState())
                && !FinancialConfigChange.VALIDATED.equals(row.getState())) {
            throw new ConflictException("only a draft is validated; this change is " + row.getState());
        }
        List<ChangeFinding> found = recheck(row);
        row.setFindings(store(found));
        if (ChartGuard.blocked(found)) {
            row.setState(FinancialConfigChange.DRAFT);
            row.setValidatedBy(null);
            row.setValidatedAt(null);
        } else {
            row.setState(FinancialConfigChange.VALIDATED);
            row.setValidatedBy(actor());
            row.setValidatedAt(OffsetDateTime.now());
        }
        changes.save(row);
        return view(row);
    }

    /** Rung three: somebody puts their name to the consequences. */
    @Transactional
    public ConfigChangeView approve(String id) {
        FinancialConfigChange row = load(id);
        if (!FinancialConfigChange.VALIDATED.equals(row.getState())) {
            throw new ConflictException("a change is approved after it validates, not before —"
                    + " this change is " + row.getState());
        }
        row.setState(FinancialConfigChange.APPROVED);
        row.setApprovedBy(actor());
        row.setApprovedAt(OffsetDateTime.now());
        changes.save(row);
        return view(row);
    }

    /** Rung four: the books change. Validation runs again first, against the live row. */
    @Transactional
    public ConfigChangeView activate(String id) {
        String tenant = tenantScope.currentTenantId();
        FinancialConfigChange row = load(id);
        if (!FinancialConfigChange.APPROVED.equals(row.getState())) {
            throw new ConflictException("only an approved change is activated — this change is "
                    + row.getState());
        }
        AccountMapping live = mappings.findByTenantIdAndMappingKey(tenant, row.getPostingKey()).orElseThrow();
        if (guard.movedUnder(live, row.getCurrentCode(), row.getCurrentName(), row.getCurrentValue())) {
            sendBack(row, ChangeFinding.blocks("The account moved since this was drafted: it now reads "
                    + live.getAccountCode() + " " + live.getAccountName()
                    + ". Draft the change again against what it says today."));
            throw new ConflictException("the account moved under this change — it is back in draft");
        }
        List<ChangeFinding> found = recheck(row);
        if (ChartGuard.blocked(found)) {
            row.setFindings(store(found));
            row.setState(FinancialConfigChange.DRAFT);
            changes.save(row);
            throw new ConflictException("validation blocks this change now — it is back in draft");
        }
        live.setAccountCode(row.getProposedCode());
        live.setAccountName(row.getProposedName());
        live.setConfigValue(row.getProposedValue());
        mappings.save(live);
        row.setFindings(store(found));
        row.setState(FinancialConfigChange.ACTIVE);
        row.setActivatedBy(actor());
        row.setActivatedAt(OffsetDateTime.now());
        changes.save(row);
        events.publish("FinancialConfigChangeActivatedEvent", "financialConfigChange", receipt(row), tenant);
        return view(row);
    }

    @Transactional
    public ConfigChangeView withdraw(String id) {
        FinancialConfigChange row = load(id);
        if (!OPEN.contains(row.getState())) {
            throw new ConflictException("a change that is " + row.getState() + " cannot be withdrawn");
        }
        row.setState(FinancialConfigChange.WITHDRAWN);
        changes.save(row);
        return view(row);
    }

    /* ---------- internals ---------- */

    private void sendBack(FinancialConfigChange row, ChangeFinding why) {
        row.setFindings(store(List.of(why)));
        row.setState(FinancialConfigChange.DRAFT);
        changes.save(row);
    }

    private List<ChangeFinding> recheck(FinancialConfigChange row) {
        String tenant = tenantScope.currentTenantId();
        AccountMapping live = mappings.findByTenantIdAndMappingKey(tenant, row.getPostingKey()).orElseThrow();
        long using = guard.postingsUsing(tenant, live.getAccountCode());
        row.setPostingsUsing(using);
        return guard.check(row.getPostingKey(), live, row.getProposedCode(), row.getProposedName(),
                row.getProposedValue(), using);
    }

    private FinancialConfigChange load(String id) {
        return changes.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("FinancialConfigChange", id));
    }

    private static BigDecimal proposedValue(ConfigChangeRequest dto, AccountMapping live) {
        if (dto.configValue() == null) {
            return live.getConfigValue();      // absent: the setting is not part of this change
        }
        if (dto.configValue().isNull()) {
            return null;                        // an explicit null clears it
        }
        try {
            return new BigDecimal(dto.configValue().asText());
        } catch (NumberFormatException e) {
            throw new BadRequestException("configValue must be a number");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String store(List<ChangeFinding> found) {
        if (found.isEmpty()) {
            return null;
        }
        return String.join("\n", found.stream().map(ChangeFinding::stored).toList());
    }

    private static List<ChangeFinding> restore(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        return stored.lines().map(ChangeFinding::parse).toList();
    }

    private static Map<String, Object> receipt(FinancialConfigChange row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.getId());
        out.put("postingKey", row.getPostingKey());
        out.put("accountCode", row.getProposedCode());
        out.put("accountName", row.getProposedName());
        out.put("activatedBy", row.getActivatedBy());
        return out;
    }

    ConfigChangeView view(FinancialConfigChange row) {
        return new ConfigChangeView(row.getId(), row.getPostingKey(), row.getCurrentName(),
                ConfigChangeWords.summary(row), row.getReason(), row.getState(),
                ConfigChangeWords.nextStep(row), restore(row.getFindings()), row.getPostingsUsing(),
                row.getCurrentCode(), row.getCurrentName(), row.getCurrentValue(),
                row.getProposedCode(), row.getProposedName(), row.getProposedValue(),
                row.getDraftedBy(), row.getDraftedAt(), row.getValidatedBy(), row.getValidatedAt(),
                row.getApprovedBy(), row.getApprovedAt(), row.getActivatedBy(), row.getActivatedAt(),
                "FinancialConfigChange");
    }

    private static String actor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return "system";
        }
        if (auth.getPrincipal() instanceof Jwt jwt) {
            String who = jwt.getClaimAsString("preferred_username");
            if (who == null || who.isBlank()) {
                who = jwt.getClaimAsString("client_id");
            }
            if (who != null && !who.isBlank()) {
                return who;
            }
        }
        return auth.getName();
    }
}
