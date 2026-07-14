package com.bss.campaign.repository;

import com.bss.campaign.entity.Journey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JourneyRepository extends JpaRepository<Journey, String> {

    Optional<Journey> findByIdAndTenantId(String id, String tenantId);

    List<Journey> findByTenantId(String tenantId);

    List<Journey> findByTenantIdAndStatusAndTriggerEventType(
            String tenantId, String status, String triggerEventType);
}
