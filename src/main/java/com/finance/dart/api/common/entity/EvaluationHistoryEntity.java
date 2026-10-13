package com.finance.dart.api.common.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 종목 평가 이력 (일자별 스냅샷)
 * - 일별 자동 전수 평가 결과를 영속화 (매수후보 목록 + 수익률 추적 기반)
 * - 종목별 1행 (프로파일 간 중복제거), 유니크(snapshot_date, symbol)
 * - 어느 프로파일이 추천했는지는 source_profiles에 태그로 보존
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "tb_evaluation_history", schema = "public",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_evaluation_history_date_symbol",
                        columnNames = {"snapshot_date", "symbol"})
        },
        indexes = {
                @Index(name = "idx_evaluation_history_date", columnList = "snapshot_date"),
                @Index(name = "idx_evaluation_history_date_signal", columnList = "snapshot_date, investment_signal"),
                @Index(name = "idx_evaluation_history_symbol", columnList = "symbol")
        })
public class EvaluationHistoryEntity {

    /** source_profiles 구분자 (프로파일명에 쓰지 않는 문자) */
    public static final String PROFILE_DELIMITER = "|";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;         // 평가 일자

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "company_name", length = 200)
    private String companyName;

    @Column(name = "sector", length = 100)
    private String sector;

    // ---- 가치×타이밍 2축 (2-1) ----
    @Column(name = "investment_signal", length = 20)
    private String investmentSignal;        // 매수 후보 / 관심목록 / 관망

    @Column(name = "value_grade", length = 4)
    private String valueGrade;              // 가치등급 S~F

    @Column(name = "value_score")
    private Double valueScore;              // 가치점수 (0~100)

    @Column(name = "timing_signal", length = 10)
    private String timingSignal;            // 양호/대기/하락/관망

    @Column(name = "timing_score")
    private Integer timingScore;            // 0~100

    // ---- 레거시 총점/등급 (하위호환) ----
    @Column(name = "total_score")
    private Double totalScore;

    @Column(name = "grade", length = 8)
    private String grade;

    // ---- 가격 (수익률 추적 기준) ----
    @Column(name = "current_price", precision = 20, scale = 4)
    private BigDecimal currentPrice;        // 평가 시점 현재가

    @Column(name = "fair_value", precision = 20, scale = 4)
    private BigDecimal fairValue;           // 적정가

    @Column(name = "purchase_price", precision = 20, scale = 4)
    private BigDecimal purchasePrice;       // 매수적정가

    /**
     * 발행주식수 - 액면분할·주식병합 판별용
     * 분할·병합 때만 배수로 변하고 실적 발표로는 변하지 않아, 조회 구간이 길어도 판별력이 유지된다.
     */
    @Column(name = "shares_outstanding", precision = 30, scale = 0)
    private BigDecimal sharesOutstanding;

    /**
     * 이 종목을 추천한 활성 프로파일명 (구분자 PROFILE_DELIMITER, 프로파일 정렬순)
     * - 2026-10-13 추가: 이전 스냅샷은 null
     */
    @Column(name = "source_profiles", length = 500)
    private String sourceProfiles;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
