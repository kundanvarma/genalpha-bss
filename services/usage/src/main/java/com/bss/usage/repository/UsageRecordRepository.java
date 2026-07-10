package com.bss.usage.repository;

import com.bss.usage.entity.UsageRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;

public interface UsageRecordRepository extends JpaRepository<UsageRecord, String> {

    List<UsageRecord> findByOwnerPartyIdAndStatusAndUsageDateBetween(
            String ownerPartyId, String status, OffsetDateTime from, OffsetDateTime to);

    List<UsageRecord> findByOwnerPartyIdAndUsageDateBetween(
            String ownerPartyId, OffsetDateTime from, OffsetDateTime to);
}
