package com.bss.billing.service;

import com.bss.billing.client.DownstreamClients;
import com.bss.billing.dto.ChannelConsentResult;
import com.bss.billing.dto.ChannelConsentResult.PartyBillingChannelView;
import com.bss.billing.dto.PartyBillingChannelRequest;
import com.bss.billing.entity.PartyBillingChannel;
import com.bss.billing.exception.BadRequestException;
import com.bss.billing.repository.PartyBillingChannelRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The consent-driven channel chain SITTING ON TOP of the tenant's
 * distribution config: e-invoice rail first (alias re-checked at EVERY
 * send — the bank's consent registry is the truth, not our row), digital
 * mailbox second, and the existing print/partner path as the floor.
 * Delivery on the rails is never guaranteed by design; the chain is the
 * design answer.
 */
@Service
public class BillChannelService {

    private final PartyBillingChannelRepository channels;
    private final DownstreamClients.AliasLookupClient aliasLookup;
    private final DownstreamClients.OrgClient orgs;

    public BillChannelService(PartyBillingChannelRepository channels,
            DownstreamClients.AliasLookupClient aliasLookup, DownstreamClients.OrgClient orgs) {
        this.channels = channels;
        this.aliasLookup = aliasLookup;
        this.orgs = orgs;
    }

    public record ResolvedChannel(String channel, String aliasRef) {
    }

    /**
     * The per-send resolution: efaktura consent + a LIVE alias -> the
     * e-invoice rail; mailbox consent -> the mailbox; nothing -> empty,
     * meaning the legacy per-tenant path (partner print/e-invoice) stands.
     */
    public Optional<ResolvedChannel> resolve(String tenantId, String partyId) {
        List<PartyBillingChannel> rows = channels.findByTenantIdAndPartyId(tenantId, partyId);
        boolean efaktura = rows.stream()
                .anyMatch(r -> PartyBillingChannel.EFAKTURA.equals(r.getChannel()));
        boolean mailbox = rows.stream()
                .anyMatch(r -> PartyBillingChannel.MAILBOX.equals(r.getChannel()));
        if (efaktura) {
            Optional<String> alias = aliasLookup.lookup(
                    orgs.partyOf(partyId).orElse(Map.of("id", partyId)));
            if (alias.isPresent()) {
                return Optional.of(new ResolvedChannel(PartyBillingChannel.EFAKTURA, alias.get()));
            }
            // alias gone at the bank = consent withdrawn THERE — fall through
        }
        if (mailbox) {
            return Optional.of(new ResolvedChannel(PartyBillingChannel.MAILBOX, null));
        }
        return Optional.empty();
    }

    /** Consent upsert; consented=false withdraws the row. */
    @Transactional
    public ChannelConsentResult upsert(String tenantId, PartyBillingChannelRequest dto) {
        String partyId = str(dto.partyId());
        String channel = str(dto.channel());
        if (partyId == null || channel == null) {
            throw new BadRequestException("partyId and channel are required");
        }
        if (!List.of(PartyBillingChannel.EFAKTURA, PartyBillingChannel.MAILBOX,
                PartyBillingChannel.PRINT).contains(channel)) {
            throw new BadRequestException("channel must be efaktura, mailbox or print");
        }
        PartyBillingChannel row = channels
                .findByTenantIdAndPartyIdAndChannel(tenantId, partyId, channel).orElse(null);
        if (Boolean.FALSE.equals(dto.consented())) {
            if (row != null) {
                channels.delete(row);
            }
            return new ChannelConsentResult.Withdrawn(partyId, channel, false);
        }
        if (row == null) {
            row = new PartyBillingChannel();
            row.setId(UUID.randomUUID().toString());
            row.setTenantId(tenantId);
            row.setPartyId(partyId);
            row.setChannel(channel);
            row.setConsentAt(OffsetDateTime.now());
            row.setCreatedAt(OffsetDateTime.now());
        }
        if (dto.aliasRef() != null) {
            row.setAliasRef(str(dto.aliasRef()));
        }
        row.setLastUpdate(OffsetDateTime.now());
        return view(channels.save(row));
    }

    @Transactional(readOnly = true)
    public List<PartyBillingChannelView> list(String tenantId, String partyId) {
        return channels.findByTenantIdAndPartyId(tenantId, partyId)
                .stream().map(this::view).toList();
    }

    private PartyBillingChannelView view(PartyBillingChannel row) {
        return new PartyBillingChannelView(row.getId(), row.getPartyId(), row.getChannel(), row.getAliasRef(),
                row.getConsentAt().toString(), "PartyBillingChannel");
    }

    private static String str(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
