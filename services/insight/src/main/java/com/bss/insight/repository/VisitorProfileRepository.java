package com.bss.insight.repository;

import com.bss.insight.entity.VisitorProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface VisitorProfileRepository extends JpaRepository<VisitorProfile, String> {

    Optional<VisitorProfile> findByTenantIdAndVisitorId(String tenantId, String visitorId);

    java.util.List<VisitorProfile> findByTenantIdAndPartyId(String tenantId, String partyId);

    java.util.List<VisitorProfile> findTop100ByTenantIdOrderByLastUpdateDesc(String tenantId);

    java.util.List<VisitorProfile> findByTenantIdAndPartyIdIsNotNull(String tenantId);

    /** All profiles (incl. anonymous, partyId null) — the visitor-population base. */
    java.util.List<VisitorProfile> findByTenantId(String tenantId);

    /** The consent ledger, paginated (newest first via the Pageable's sort). */
    org.springframework.data.domain.Page<VisitorProfile> findByTenantId(
            String tenantId, org.springframework.data.domain.Pageable pageable);

    /** Search the ledger by visitor id or party id (q is a lowercased %like% term). */
    @org.springframework.data.jpa.repository.Query("SELECT p FROM VisitorProfile p WHERE p.tenantId = :t"
            + " AND (LOWER(p.visitorId) LIKE :q OR LOWER(COALESCE(p.partyId, '')) LIKE :q)")
    org.springframework.data.domain.Page<VisitorProfile> search(
            @org.springframework.data.repository.query.Param("t") String tenantId,
            @org.springframework.data.repository.query.Param("q") String q,
            org.springframework.data.domain.Pageable pageable);
}
