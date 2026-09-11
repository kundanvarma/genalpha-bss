package com.bss.entitlement.repository;

import com.bss.entitlement.entity.SubscriptionTransfer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubscriptionTransferRepository extends JpaRepository<SubscriptionTransfer, String> {

    List<SubscriptionTransfer> findByTenantIdAndImsiOrderByCreatedAtDesc(String tenantId, String imsi);

    List<SubscriptionTransfer> findTop200ByTenantIdOrderByCreatedAtDesc(String tenantId);
}
