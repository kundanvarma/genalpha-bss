package com.bss.billing.repository;

import com.bss.billing.entity.CreditNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CreditNoteRepository extends JpaRepository<CreditNote, String> {

    Optional<CreditNote> findByIdAndTenantId(String id, String tenantId);

    List<CreditNote> findByTenantIdOrderByIssuedAtDesc(String tenantId);

    List<CreditNote> findByTenantIdAndOwnerPartyIdOrderByIssuedAtDesc(String tenantId, String ownerPartyId);

    List<CreditNote> findByTenantIdAndBillIdOrderByIssuedAtDesc(String tenantId, String billId);

    boolean existsByTenantIdAndReason(String tenantId, String reason);
}
