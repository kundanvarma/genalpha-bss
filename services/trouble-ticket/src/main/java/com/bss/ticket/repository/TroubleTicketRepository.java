package com.bss.ticket.repository;

import com.bss.ticket.entity.TroubleTicket;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TroubleTicketRepository extends JpaRepository<TroubleTicket, String> {
}
