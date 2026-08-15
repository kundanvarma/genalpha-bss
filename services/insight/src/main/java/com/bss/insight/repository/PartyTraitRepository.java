package com.bss.insight.repository;

import com.bss.insight.entity.PartyTrait;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface PartyTraitRepository extends JpaRepository<PartyTrait, String> {

    boolean existsByTenantIdAndPartyIdAndTraitKeyAndTraitValue(
            String tenantId, String partyId, String traitKey, String traitValue);

    List<PartyTrait> findByTenantIdAndPartyId(String tenantId, String partyId);

    List<PartyTrait> findByTenantId(String tenantId);

    /** One query for a whole trait key — e.g. every party's email, for a batch
     * activation export (no per-member lookup). */
    List<PartyTrait> findByTenantIdAndTraitKey(String tenantId, String traitKey);

    /** Clear a single-valued trait before writing the new value (tier, band, spend).
     * Bulk DML so the DELETE executes immediately — a derived delete marks rows in
     * the persistence context and Hibernate flushes the follow-up INSERT first,
     * which collides with uq_party_trait when the value is unchanged (backfill). */
    @Modifying
    @Query("delete from PartyTrait t where t.tenantId = ?1 and t.partyId = ?2 and t.traitKey = ?3")
    void deleteByTenantIdAndPartyIdAndTraitKey(String tenantId, String partyId, String traitKey);

    /** Distinct party ids that carry any trait — the BSS-native candidate base. */
    @Query("select distinct t.partyId from PartyTrait t where t.tenantId = ?1")
    List<String> distinctPartyIds(String tenantId);

    /** Distinct (key, value) pairs so the builder can offer real choices. */
    @Query("select distinct t.traitKey, t.traitValue from PartyTrait t where t.tenantId = ?1 order by t.traitKey")
    List<Object[]> distinctKeyValues(String tenantId);
}
