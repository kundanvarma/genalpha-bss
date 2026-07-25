package com.bss.loyalty.repository;

import com.bss.loyalty.entity.LoyaltyProgram;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LoyaltyProgramRepository extends JpaRepository<LoyaltyProgram, String> {
    Optional<LoyaltyProgram> findByTenantId(String tenantId);
}
