package com.bss.party.repository;

import com.bss.party.entity.BillFormat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BillFormatRepository extends JpaRepository<BillFormat, String> {
}
