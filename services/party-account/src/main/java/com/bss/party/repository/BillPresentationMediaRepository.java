package com.bss.party.repository;

import com.bss.party.entity.BillPresentationMedia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BillPresentationMediaRepository extends JpaRepository<BillPresentationMedia, String> {
}
