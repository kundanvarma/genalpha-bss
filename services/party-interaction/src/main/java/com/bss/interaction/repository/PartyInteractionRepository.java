package com.bss.interaction.repository;

import com.bss.interaction.entity.PartyInteraction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PartyInteractionRepository extends JpaRepository<PartyInteraction, String> {

    Optional<PartyInteraction> findByIdAndTenantId(String id, String tenantId);
}
