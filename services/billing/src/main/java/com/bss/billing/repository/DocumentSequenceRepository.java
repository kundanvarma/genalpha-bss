package com.bss.billing.repository;

import com.bss.billing.entity.DocumentSequence;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DocumentSequenceRepository extends JpaRepository<DocumentSequence, DocumentSequence.Key> {

    /** The gapless increment: lock the row, read, bump — all in one tx. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from DocumentSequence s where s.tenantId = :tenant and s.series = :series")
    Optional<DocumentSequence> lockedRow(@Param("tenant") String tenant, @Param("series") String series);
}
