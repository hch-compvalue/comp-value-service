package com.finance.dart.api.common.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 스케줄 실행 이력 (자동/수동 실행 1회 = 1행)
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "tb_schedule_run_history", schema = "public",
        indexes = {
                @Index(name = "idx_schedule_run_history_job_started", columnList = "job_key, started_at")
        })
public class ScheduleRunHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_key", nullable = false, length = 50)
    private String jobKey;                  // ScheduleJobType 이름

    @Column(name = "trigger_type", nullable = false, length = 10)
    private String triggerType;             // AUTO / MANUAL

    @Column(name = "max_count")
    private Integer maxCount;               // 수동 실행 처리 건수 제한 (0 = 전체)

    @Column(name = "status", nullable = false, length = 20)
    private String status;                  // RUNNING / SUCCESS / FAILED / INTERRUPTED

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "message", length = 500)
    private String message;                 // 결과 요약
}
