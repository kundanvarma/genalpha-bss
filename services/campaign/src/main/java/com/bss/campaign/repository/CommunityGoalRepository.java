package com.bss.campaign.repository;

import com.bss.campaign.entity.CommunityGoal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CommunityGoalRepository extends JpaRepository<CommunityGoal, String> {
    List<CommunityGoal> findByTenantId(String tenantId);
    Optional<CommunityGoal> findByIdAndTenantId(String id, String tenantId);
}
