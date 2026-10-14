package com.finance.dart.api.common.repository;

import com.finance.dart.api.common.entity.ScheduleRunHistoryEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ScheduleRunHistoryRepository extends JpaRepository<ScheduleRunHistoryEntity, Long> {

    /**
     * 작업별 최근 실행 이력 (최신순)
     */
    List<ScheduleRunHistoryEntity> findByJobKeyOrderByStartedAtDesc(String jobKey, Pageable pageable);

    /**
     * 작업별 마지막 실행 1건
     */
    Optional<ScheduleRunHistoryEntity> findFirstByJobKeyOrderByStartedAtDesc(String jobKey);

    /**
     * 특정 상태의 이력 전체 (기동 시 중단된 RUNNING 정리용)
     */
    List<ScheduleRunHistoryEntity> findByStatus(String status);
}
