package com.finance.dart.monitoring.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 외부 API 모니터링 사용 여부 (실시간 대시보드에서 끄고 켬)
 * - 행이 없으면 사용(Y)으로 봄 — 끈 API 만 행이 생김
 * - 모니터링 헬스체크만 멈춤, 실제 기능의 API 호출은 막지 않음
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "tb_external_api_setting", schema = "public")
public class ExternalApiSettingEntity {

    @Id
    @Column(name = "api_name", length = 30)
    private String apiName;                 // FMP / DART / Upbit / OpenAI

    @Column(name = "enabled", nullable = false, length = 1)
    private String enabled;                 // Y/N

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();  // 수정일시

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
