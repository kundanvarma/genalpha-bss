package com.bss.hub.repository;

import com.bss.hub.entity.HubDelivery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;

public interface HubDeliveryRepository extends JpaRepository<HubDelivery, String> {
    List<HubDelivery> findTop50ByStatusAndNextAttemptAtBeforeOrderByNextAttemptAtAsc(
            String status, OffsetDateTime before);
    List<HubDelivery> findTop100ByTenantIdAndSubscriptionIdOrderByCreatedAtDesc(
            String tenantId, String subscriptionId);
}
