package com.bss.intelligence.churn;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChurnModelRepository extends JpaRepository<ChurnModelRecord, String> {
}
