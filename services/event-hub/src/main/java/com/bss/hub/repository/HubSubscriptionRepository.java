package com.bss.hub.repository;

import com.bss.hub.entity.HubSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HubSubscriptionRepository extends JpaRepository<HubSubscription, String> {
    Optional<HubSubscription> findByIdAndTenantId(String id, String tenantId);
    List<HubSubscription> findByTenantIdAndActiveTrue(String tenantId);
    List<HubSubscription> findByTenantIdOrderByCreatedAtDesc(String tenantId);
}
