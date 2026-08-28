package com.bss.communication.repository;

import com.bss.communication.entity.CommunicationMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CommunicationMessageRepository extends JpaRepository<CommunicationMessage, String> {

    Optional<CommunicationMessage> findByIdAndTenantId(String id, String tenantId);

    boolean existsByTenantIdAndSourceEventId(String tenantId, String sourceEventId);

    java.util.List<CommunicationMessage> findByTenantIdAndReceiverPartyId(String tenantId, String receiverPartyId);

    /** Contact-frequency governor: how many MARKETING messages this party got
     * recently. Marketing rides the martech door (send/sendTemplated) and has
     * no sourceEventId; transactional mail is minted from a domain event and
     * always carries one — it must never eat the marketing budget, or a
     * customer who just ordered (order received/complete/bill) becomes
     * unreachable for the welcome journey that fired on that very order. */
    long countByTenantIdAndReceiverPartyIdAndSourceEventIdIsNullAndCreatedAtAfter(
            String tenantId, String receiverPartyId, java.time.OffsetDateTime since);
}
