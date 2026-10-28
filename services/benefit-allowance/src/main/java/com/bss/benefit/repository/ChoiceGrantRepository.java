package com.bss.benefit.repository;

import com.bss.benefit.entity.ChoiceGrant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChoiceGrantRepository extends JpaRepository<ChoiceGrant, String> {

    List<ChoiceGrant> findByTenantIdAndPoolIdAndState(String tenantId, String poolId, String state);

    Optional<ChoiceGrant> findByTenantIdAndOrderIdAndOrderItemId(String tenantId, String orderId, String orderItemId);
}
