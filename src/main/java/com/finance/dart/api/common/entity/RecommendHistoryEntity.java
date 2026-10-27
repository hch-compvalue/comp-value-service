package com.finance.dart.api.common.entity;

import com.finance.dart.api.common.constants.Market;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 추천 종목 이력 (일자별 스냅샷)
 * - 스케줄러가 Redis 저장과 함께 DB에도 영속화 (서버 재시작에도 보존, 수익률 추적 기반)
 * - 종목별 1행, 유니크(snapshot_date, profile_name, symbol)로 같은 날 재실행은 교체(멱등)
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "tb_recommend_history", schema = "public",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_recommend_history_date_profile_symbol",
                        columnNames = {"snapshot_date", "profile_name", "symbol"})
        },
        indexes = {
                @Index(name = "idx_recommend_history_date_profile", columnList = "snapshot_date, profile_name"),
                @Index(name = "idx_recommend_history_symbol", columnList = "symbol")
        })
public class RecommendHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;                        // PK

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;         // 스냅샷 일자 (스케줄 실행일)

    @Column(name = "profile_name", nullable = false, length = 100)
    private String profileName;             // 프로파일명

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;                  // 티커

    @Enumerated(EnumType.STRING)
    @Column(name = "market", nullable = false, length = 2, columnDefinition = Market.COLUMN_DEFINITION)
    private Market market = Market.US;  // 시장 (US/KR, 기존 행은 US)

    @Column(name = "company_name", length = 200)
    private String companyName;             // 기업명

    @Column(name = "price", precision = 20, scale = 4)
    private BigDecimal price;               // 추천 시점 가격 (수익률 계산 기준가)

    @Column(name = "sector", length = 100)
    private String sector;                  // 섹터

    @Column(name = "data_json", columnDefinition = "TEXT")
    private String dataJson;                // 전체 RecommendedStockData JSON (폴백 시 완전 복원용)

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();  // 저장일시

    /** 시장 미지정 저장 방지 — 기존 저장 경로(미국)는 US */
    @PrePersist
    private void defaultMarket() {
        if (market == null) market = Market.US;
    }
}
