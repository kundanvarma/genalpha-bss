package com.bss.som.repository;

import com.bss.som.entity.ProviderAccessOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;

public interface ProviderAccessOrderRepository extends JpaRepository<ProviderAccessOrder, String> {
    List<ProviderAccessOrder> findByTenantId(String tenantId);
    List<ProviderAccessOrder> findByTenantIdAndState(String tenantId, String state);
    List<ProviderAccessOrder> findTop100ByTenantIdAndStateAndActivateAtBefore(
            String tenantId, String state, OffsetDateTime before);
}
