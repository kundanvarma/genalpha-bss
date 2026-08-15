package com.bss.communication.repository;

import com.bss.communication.entity.CommunicationMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CommunicationMessageRepository extends JpaRepository<CommunicationMessage, String> {

    Optional<CommunicationMessage> findByIdAndTenantId(String id, String tenantId);

    boolean existsByTenantIdAndSourceEventId(String tenantId, String sourceEventId);

    java.util.List<CommunicationMessage> findByTenantIdAndReceiverPartyId(String tenantId, String receiverPartyId);

    /** Contact-frequency governor: how many messages this party got recently. */
    long countByTenantIdAndReceiverPartyIdAndCreatedAtAfter(
            String tenantId, String receiverPartyId, java.time.OffsetDateTime since);
}
