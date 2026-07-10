package com.bss.interaction.repository;

import com.bss.interaction.entity.PartyInteraction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartyInteractionRepository extends JpaRepository<PartyInteraction, String> {
}
