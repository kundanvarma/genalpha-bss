package com.bss.entitlement.repository;

import com.bss.entitlement.entity.EntitlementSubscriber;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EntitlementSubscriberRepository extends JpaRepository<EntitlementSubscriber, String> {

    Optional<EntitlementSubscriber> findByTenantIdAndImsi(String tenantId, String imsi);

    List<EntitlementSubscriber> findByTenantIdAndPartyId(String tenantId, String partyId);

    List<EntitlementSubscriber> findByTenantIdAndServiceId(String tenantId, String serviceId);

    List<EntitlementSubscriber> findByTenantIdOrderByLastUpdateDesc(String tenantId);
}
