package com.bss.loyalty.repository;

import com.bss.loyalty.entity.LoyaltyMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface LoyaltyMemberRepository extends JpaRepository<LoyaltyMember, LoyaltyMember.Key> {
    Optional<LoyaltyMember> findByIdAndTenantId(String id, String tenantId);

    @Query("select coalesce(sum(m.balance),0) from LoyaltyMember m where m.tenantId = :tenant")
    long liability(@Param("tenant") String tenant);
}
