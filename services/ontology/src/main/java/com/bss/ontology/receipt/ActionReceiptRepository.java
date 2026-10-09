package com.bss.ontology.receipt;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The receipt store (ADR 0025). Reads and appends only — there is deliberately
 * no update or delete here, because a receipt that can be rewritten is not
 * evidence.
 */
public interface ActionReceiptRepository extends JpaRepository<ActionReceiptRow, String> {

    List<ActionReceiptRow> findByTenantIdAndReceiptIdOrderByRecordedAtAsc(String tenantId, String receiptId);

    /** The chain head for a tenant: the last row written, whose hash the next one links to. */
    @Query("""
           select r from ActionReceiptRow r
            where r.tenantId = :tenantId
            order by r.recordedAt desc, r.id desc
           limit 1
           """)
    ActionReceiptRow chainHead(@Param("tenantId") String tenantId);

    /**
     * Attempts with no outcome row, older than a cutoff — the reconciler's work
     * list. An action whose outcome write failed looks exactly like one whose
     * component never answered, and both need resolving against the far side
     * using the idempotency key.
     */
    @Query("""
           select a from ActionReceiptRow a
            where a.phase = 'attempt'
              and a.recordedAt < :cutoff
              and not exists (select 1 from ActionReceiptRow o
                               where o.receiptId = a.receiptId and o.phase = 'outcome')
            order by a.recordedAt asc
           """)
    List<ActionReceiptRow> unresolvedAttempts(@Param("cutoff") OffsetDateTime cutoff);

    /** Whole-chain read for the verifier, oldest first. */
    List<ActionReceiptRow> findByTenantIdOrderByRecordedAtAscIdAsc(String tenantId);
}
