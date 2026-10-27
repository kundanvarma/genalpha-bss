package com.bss.benefit.repository;

import com.bss.benefit.entity.ChoiceAllocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ChoiceAllocationRepository extends JpaRepository<ChoiceAllocation, String> {

    List<ChoiceAllocation> findByTenantIdAndPoolIdAndStateInOrderByCreatedAtAsc(String tenantId, String poolId,
            Collection<String> states);

    List<ChoiceAllocation> findByTenantIdAndOrderIdAndState(String tenantId, String orderId, String state);

    Optional<ChoiceAllocation> findByTenantIdAndOrderIdAndOrderItemId(String tenantId, String orderId,
            String orderItemId);

    long countByTenantIdAndPoolIdAndEndReasonAndEndedAtGreaterThanEqual(String tenantId, String poolId,
            String endReason, java.time.OffsetDateTime since);

    List<ChoiceAllocation> findByTenantIdAndState(String tenantId, String state);
}
