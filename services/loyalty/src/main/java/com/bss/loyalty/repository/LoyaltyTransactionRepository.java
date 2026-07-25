package com.bss.loyalty.repository;

import com.bss.loyalty.entity.LoyaltyTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoyaltyTransactionRepository extends JpaRepository<LoyaltyTransaction, String> {
    boolean existsByTenantIdAndCause(String tenantId, String cause);
    List<LoyaltyTransaction> findTop50ByTenantIdAndPartyIdOrderByCreatedAtDesc(String tenantId, String partyId);

    @org.springframework.data.jpa.repository.Query(
        "select coalesce(sum(t.points),0) from LoyaltyTransaction t where t.tenantId = :tenant"
        + " and t.partyId = :party and t.points > 0 and t.createdAt >= :since")
    long earnedSince(@org.springframework.data.repository.query.Param("tenant") String tenant,
        @org.springframework.data.repository.query.Param("party") String party,
        @org.springframework.data.repository.query.Param("since") java.time.OffsetDateTime since);

    @org.springframework.data.jpa.repository.Query(
        "select coalesce(sum(t.points),0) from LoyaltyTransaction t where t.tenantId = :tenant"
        + " and t.partyId = :party and t.points > 0 and t.createdAt < :before")
    long earnedBefore(@org.springframework.data.repository.query.Param("tenant") String tenant,
        @org.springframework.data.repository.query.Param("party") String party,
        @org.springframework.data.repository.query.Param("before") java.time.OffsetDateTime before);

    @org.springframework.data.jpa.repository.Query(
        "select coalesce(sum(-t.points),0) from LoyaltyTransaction t where t.tenantId = :tenant"
        + " and t.partyId = :party and t.points < 0")
    long spentTotal(@org.springframework.data.repository.query.Param("tenant") String tenant,
        @org.springframework.data.repository.query.Param("party") String party);
}
