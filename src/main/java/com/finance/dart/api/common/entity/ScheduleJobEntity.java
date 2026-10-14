package com.finance.dart.api.common.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 스케줄 작업 설정 (화면에서 변경하는 실행 주기·사용 여부)
 * - 작업 정의(이름·설명·기본 주기)는 코드(ScheduleJobType), DB에는 운영 중 바꾸는 값만 보관
 * - 행이 없으면 기동 시 기본값으로 생성
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "tb_schedule_job", schema = "public")
public class ScheduleJobEntity {

    @Id
    @Column(name = "job_key", length = 50)
    private String jobKey;                  // ScheduleJobType 이름

    @Column(name = "cron_expression", nullable = false, length = 100)
    private String cronExpression;          // Spring cron (초 분 시 일 월 요일)

    @Column(name = "enabled", nullable = false, length = 1)
    private String enabled;                 // Y/N

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();  // 수정일시

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
