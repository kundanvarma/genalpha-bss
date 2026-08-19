package com.bss.insight.repository;

import com.bss.insight.entity.CustomerSignal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CustomerSignalRepository extends JpaRepository<CustomerSignal, String> {

    Optional<CustomerSignal> findByTenantIdAndDedupHash(String tenantId, String dedupHash);

    List<CustomerSignal> findTop100ByTenantIdOrderByReceivedAtDesc(String tenantId);

    List<CustomerSignal> findTop100ByTenantIdAndSourceOrderByReceivedAtDesc(String tenantId, String source);

    List<CustomerSignal> findByTenantIdAndPartyId(String tenantId, String partyId);

    long deleteByTenantIdAndPartyId(String tenantId, String partyId);
}
