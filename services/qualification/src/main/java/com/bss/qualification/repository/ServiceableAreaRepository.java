package com.bss.qualification.repository;

import com.bss.qualification.entity.ServiceableArea;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ServiceableAreaRepository extends JpaRepository<ServiceableArea, String> {

    List<ServiceableArea> findByProductOfferingId(String productOfferingId);
}
