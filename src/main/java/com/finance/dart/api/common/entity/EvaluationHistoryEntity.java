package com.finance.dart.api.common.entity;

import com.finance.dart.api.common.constants.Market;
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

    @Enumerated(EnumType.STRING)
    @Column(name = "market", nullable = false, length = 2, columnDefinition = Market.COLUMN_DEFINITION)
    private Market market = Market.US;  // 시장 (US/KR, 기존 행은 US)

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

    // ── 투자 순위 (2026-10-14): 그날 후보 안 백분위 평균 = (수익성 + 가치 + 단기 눌림) / 3 ──
    // 백테스트(test/backtest-v8-2026-10 스윙 분석)로 2~4주 순위 예측력 확인된 요소만 사용. 10-14 이전 행은 null

    @Column(name = "roe")
    private Double roe;                     // ROE TTM (추천 수집 시 FMP ratios-ttm 값, 비율)

    @Column(name = "return_1w_pct")
    private Double return1wPct;             // 최근 5거래일 수익률 (%)

    @Column(name = "roe_pct")
    private Double roePct;                  // 후보 안 ROE 백분위 (0~100, 높을수록 수익성 상위)

    @Column(name = "value_pct")
    private Double valuePct;                // 후보 안 가치점수 백분위 (0~100)

    @Column(name = "pullback_pct")
    private Double pullbackPct;             // 후보 안 단기 눌림 백분위 (0~100, 1주 하락이 클수록 높음)

    @Column(name = "rank_score")
    private Double rankScore;               // 투자 순위 점수 (0~100, 세 백분위 평균. 하나라도 없으면 null)

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** 시장 미지정 저장 방지 — 기존 저장 경로(미국)는 US */
    @PrePersist
    private void defaultMarket() {
        if (market == null) market = Market.US;
    }
}
