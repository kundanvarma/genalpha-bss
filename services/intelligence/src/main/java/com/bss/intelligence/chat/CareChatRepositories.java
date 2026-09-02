package com.bss.intelligence.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface CareChatSessionRepository extends JpaRepository<CareChatSession, String> {
    List<CareChatSession> findTop50ByTenantIdAndStatusInOrderByUpdatedAtDesc(
            String tenantId, List<String> statuses);
}

interface CareChatMessageRepository extends JpaRepository<CareChatMessage, String> {
    List<CareChatMessage> findBySessionIdOrderByCreatedAtAsc(String sessionId);
}
