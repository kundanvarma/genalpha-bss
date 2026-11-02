package com.bss.ordering.repository;

import com.bss.ordering.entity.CreditDecision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CreditDecisionRepository extends JpaRepository<CreditDecision, String> {

    List<CreditDecision> findByTenantIdAndPartyIdOrderByDecidedAtDesc(String tenantId, String partyId);

    /** The decision taken for one order (the latest, should a retry have taken two). */
    Optional<CreditDecision> findFirstByTenantIdAndOrderIdOrderByDecidedAtDesc(String tenantId, String orderId);

    /**
     * Claim the one review request a decision may carry: 1 when this call
     * claimed it, 0 when someone already had. The row lock makes a double
     * click wait for the first and then see it taken — one ticket per order.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update CreditDecision c set c.reviewRequestedAt = :at"
            + " where c.id = :id and c.tenantId = :tenantId and c.reviewRequestedAt is null")
    int claimReview(@Param("id") String id, @Param("tenantId") String tenantId, @Param("at") OffsetDateTime at);
}
