package com.finance.dart.api.common.repository;

import com.finance.dart.api.common.entity.ScheduleJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduleJobRepository extends JpaRepository<ScheduleJobEntity, String> {
}
