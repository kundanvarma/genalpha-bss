package com.bss.campaign.repository;

import com.bss.campaign.entity.JourneyEnrollment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface JourneyEnrollmentRepository extends JpaRepository<JourneyEnrollment, String> {

    boolean existsByTenantIdAndJourneyIdAndPartyId(String tenantId, String journeyId, String partyId);

    List<JourneyEnrollment> findByTenantIdAndJourneyId(String tenantId, String journeyId);

    List<JourneyEnrollment> findByTenantIdAndPartyIdAndStatus(
            String tenantId, String partyId, String status);

    /** The tick's worklist: everyone whose next step is due, per tenant
     * (the tick acts as each tenant in turn, so RLS stays honest). */
    List<JourneyEnrollment> findTop200ByTenantIdAndStatusAndNextActionAtBefore(
            String tenantId, String status, OffsetDateTime cutoff);
}
