package com.bss.benefit.repository;

import com.bss.benefit.entity.ChoicePool;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ChoicePoolRepository extends JpaRepository<ChoicePool, String> {

    Optional<ChoicePool> findByTenantIdAndOwnerPartyId(String tenantId, String ownerPartyId);

    /** The pool row, locked for the rest of the transaction: every hold goes through here, one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ChoicePool p where p.tenantId = :tenantId and p.ownerPartyId = :owner")
    Optional<ChoicePool> lockByOwner(@Param("tenantId") String tenantId, @Param("owner") String ownerPartyId);
}
