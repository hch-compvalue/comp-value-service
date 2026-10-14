package com.finance.dart.api.common.dto.schedule;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 스케줄 작업 상태 (관리 화면 목록 1행)
 */
@Data
public class ScheduleJobDto {
    private String jobKey;
    private String label;
    private String description;
    private String cronExpression;
    private String defaultCron;
    private boolean enabled;
    private boolean running;
    private LocalDateTime nextRunAt;            // 비활성이면 null
    private LocalDateTime updatedAt;
    private ScheduleRunHistoryDto lastRun;      // 실행 이력 없으면 null
}
