package com.bss.inventory.repository;

import com.bss.inventory.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, String> {

    Optional<Product> findByIdAndTenantId(String id, String tenantId);

    /**
     * Everything a party can SEE: what they own, plus what they PAY for
     * (household/company payer stamp in the related-party JSON). The broad
     * LIKE is safe: a false positive requires the party to appear on a
     * payer-stamped product in some role — and every such role already
     * entitles them to see it.
     */
    @org.springframework.data.jpa.repository.Query(
        "select p from Product p where p.tenantId = :tenantId and (p.ownerPartyId = :partyId"
        + " or (p.relatedPartyJson like concat('%', :partyId, '%')"
        + " and p.relatedPartyJson like '%\"payer\"%'))")
    org.springframework.data.domain.Page<Product> findVisibleToParty(
        @org.springframework.data.repository.query.Param("tenantId") String tenantId,
        @org.springframework.data.repository.query.Param("partyId") String partyId,
        org.springframework.data.domain.Pageable pageable);

    /** A member's HOUSEHOLD-FUNDED products only: owned by them, stamped with
     * the family payer. What they bought with their own money stays theirs. */
    @org.springframework.data.jpa.repository.Query(
        "select p from Product p where p.tenantId = :tenantId and p.ownerPartyId = :ownerId"
        + " and p.relatedPartyJson like concat('%', :payerId, '%')"
        + " and p.relatedPartyJson like '%\"payer\"%'")
    org.springframework.data.domain.Page<Product> findFundedFor(
        @org.springframework.data.repository.query.Param("tenantId") String tenantId,
        @org.springframework.data.repository.query.Param("ownerId") String ownerId,
        @org.springframework.data.repository.query.Param("payerId") String payerId,
        org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<Product> findByTenantIdAndOwnerPartyId(
        String tenantId, String ownerPartyId, org.springframework.data.domain.Pageable pageable);
}
