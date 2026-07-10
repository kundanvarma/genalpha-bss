package com.bss.ticket.repository;

import com.bss.ticket.entity.TroubleTicket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TroubleTicketRepository extends JpaRepository<TroubleTicket, String> {

    Optional<TroubleTicket> findByIdAndTenantId(String id, String tenantId);
}
