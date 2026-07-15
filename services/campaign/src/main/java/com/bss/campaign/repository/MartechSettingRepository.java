package com.bss.campaign.repository;

import com.bss.campaign.entity.MartechSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MartechSettingRepository extends JpaRepository<MartechSetting, String> {
}
