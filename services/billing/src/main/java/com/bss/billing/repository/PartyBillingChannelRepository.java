package com.bss.billing.repository;

import com.bss.billing.entity.PartyBillingChannel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PartyBillingChannelRepository extends JpaRepository<PartyBillingChannel, String> {

    List<PartyBillingChannel> findByTenantIdAndPartyId(String tenantId, String partyId);

    Optional<PartyBillingChannel> findByTenantIdAndPartyIdAndChannel(String tenantId, String partyId, String channel);
}
