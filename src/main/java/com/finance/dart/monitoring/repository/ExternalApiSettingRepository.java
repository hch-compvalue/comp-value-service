package com.finance.dart.monitoring.repository;

import com.finance.dart.monitoring.entity.ExternalApiSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExternalApiSettingRepository extends JpaRepository<ExternalApiSettingEntity, String> {
}
