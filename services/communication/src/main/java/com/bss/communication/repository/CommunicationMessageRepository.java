package com.bss.communication.repository;

import com.bss.communication.entity.CommunicationMessage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommunicationMessageRepository extends JpaRepository<CommunicationMessage, String> {

    boolean existsBySourceEventId(String sourceEventId);
}
