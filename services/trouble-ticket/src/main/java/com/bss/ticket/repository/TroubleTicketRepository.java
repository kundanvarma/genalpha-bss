package com.bss.ticket.repository;

import com.bss.ticket.entity.TroubleTicket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TroubleTicketRepository extends JpaRepository<TroubleTicket, String> {

    Optional<TroubleTicket> findByIdAndTenantId(String id, String tenantId);

    java.util.List<TroubleTicket> findByTenantIdAndOwnerPartyId(String tenantId, String ownerPartyId);

    java.util.List<TroubleTicket> findByStatusInAndLastUpdateBefore(java.util.List<String> statuses, java.time.OffsetDateTime cutoff);
}
