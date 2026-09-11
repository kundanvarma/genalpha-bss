package com.bss.entitlement.repository;

import com.bss.entitlement.entity.EcsRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EcsRequestRepository extends JpaRepository<EcsRequest, String> {

    List<EcsRequest> findTop200ByTenantIdOrderByCreatedAtDesc(String tenantId);

    List<EcsRequest> findTop50ByTenantIdAndImsiOrderByCreatedAtDesc(String tenantId, String imsi);
}
