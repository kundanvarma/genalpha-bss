package com.bss.som.repository;

import com.bss.som.entity.NumberQuarantine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NumberQuarantineRepository extends JpaRepository<NumberQuarantine, String> {
}
