package com.bss.insight.repository;

import com.bss.insight.entity.VisitorProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface VisitorProfileRepository extends JpaRepository<VisitorProfile, String> {

    Optional<VisitorProfile> findByTenantIdAndVisitorId(String tenantId, String visitorId);

    java.util.List<VisitorProfile> findByTenantIdAndPartyId(String tenantId, String partyId);
}
