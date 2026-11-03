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

    Optional<CreditDecision> findByIdAndTenantId(String id, String tenantId);

    /** The decisions taken for a page of orders, newest first. */
    List<CreditDecision> findByTenantIdAndOrderIdInOrderByDecidedAtDesc(String tenantId,
            java.util.Collection<String> orderIds);

    /**
     * Claim the one review request a decision may carry: 1 when this call
     * claimed it, 0 when someone already had. Committed on its own, BEFORE the
     * ticket call — no row lock is held while the care queue answers.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update CreditDecision c set c.reviewRequestedAt = :at"
            + " where c.id = :id and c.tenantId = :tenantId and c.reviewRequestedAt is null")
    int claimReview(@Param("id") String id, @Param("tenantId") String tenantId, @Param("at") OffsetDateTime at);

    /**
     * Take over a claim that never got its ticket (the attempt died, or is
     * older than any ticket call can take): compare-and-set on the claim's own
     * time, so two takers cannot both win.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update CreditDecision c set c.reviewRequestedAt = :at"
            + " where c.id = :id and c.tenantId = :tenantId and c.reviewRequestedAt = :was"
            + " and c.reviewTicketId is null")
    int reclaimReview(@Param("id") String id, @Param("tenantId") String tenantId,
            @Param("was") OffsetDateTime was, @Param("at") OffsetDateTime at);

    /** A ticket call that failed gives the claim back, so the customer can simply try again. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update CreditDecision c set c.reviewRequestedAt = null"
            + " where c.id = :id and c.tenantId = :tenantId and c.reviewRequestedAt = :at"
            + " and c.reviewTicketId is null")
    int releaseReview(@Param("id") String id, @Param("tenantId") String tenantId, @Param("at") OffsetDateTime at);

    /** The ticket the claim was for, recorded once it exists — only by the attempt holding the claim. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update CreditDecision c set c.reviewTicketId = :ticket"
            + " where c.id = :id and c.tenantId = :tenantId and c.reviewTicketId is null")
    int recordReviewTicket(@Param("id") String id, @Param("tenantId") String tenantId,
            @Param("ticket") String ticket);
}
