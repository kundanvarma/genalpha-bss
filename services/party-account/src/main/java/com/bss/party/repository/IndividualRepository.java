package com.bss.party.repository;

import com.bss.party.entity.Individual;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface IndividualRepository extends JpaRepository<Individual, String> {

    Optional<Individual> findByIdAndTenantId(String id, String tenantId);
}
