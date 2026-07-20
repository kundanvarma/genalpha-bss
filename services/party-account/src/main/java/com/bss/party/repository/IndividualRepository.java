package com.bss.party.repository;

import com.bss.party.entity.Individual;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface IndividualRepository extends JpaRepository<Individual, String> {

    /** Trigram similarity over the full name — the typo net under the
     * strict search. Postgres-only (pg_trgm); callers catch and shrug. */
    @org.springframework.data.jpa.repository.Query(value =
        "SELECT * FROM individual i WHERE i.tenant_id = :tenantId"
        + " AND similarity(lower(coalesce(i.given_name,'') || ' ' || coalesce(i.family_name,'')), lower(:q)) > 0.3"
        + " ORDER BY similarity(lower(coalesce(i.given_name,'') || ' ' || coalesce(i.family_name,'')), lower(:q)) DESC"
        + " LIMIT 10", nativeQuery = true)
    java.util.List<Individual> searchFuzzy(@org.springframework.data.repository.query.Param("tenantId") String tenantId,
            @org.springframework.data.repository.query.Param("q") String q);

    Optional<Individual> findByIdAndTenantId(String id, String tenantId);

    java.util.List<Individual> findByTenantIdAndHouseholdPayerId(String tenantId, String payerId);

    java.util.List<Individual> findByTenantIdAndContactMediumJsonContaining(String tenantId, String email);

    /** The CSR's search box: any fragment of a name, or anything that lives
     * in the contact medium (email, address, postal code) — case-blind. */
    @org.springframework.data.jpa.repository.Query(
        "select i from Individual i where i.tenantId = :tenantId and ("
        + " lower(i.givenName) like lower(concat('%', :q, '%'))"
        + " or lower(i.familyName) like lower(concat('%', :q, '%'))"
        + " or lower(i.contactMediumJson) like lower(concat('%', :q, '%')))")
    java.util.List<Individual> searchLoose(
        @org.springframework.data.repository.query.Param("tenantId") String tenantId,
        @org.springframework.data.repository.query.Param("q") String q);
}
