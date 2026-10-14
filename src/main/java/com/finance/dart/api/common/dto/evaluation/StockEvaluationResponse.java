package com.finance.dart.api.common.dto.evaluation;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.finance.dart.api.common.dto.CompanySharePriceResultDetail;
import lombok.*;

import java.util.List;

/**
 * 종목 평가 응답 DTO
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonPropertyOrder({
    "symbol", "companyName", "currentPrice", "fairValue", "priceDifference",
    "priceGapPercent", "totalScore", "grade", "recommendation",
    "purchasePrice", "sellTarget", "stopLossPrice",
    "peg", "per", "sector", "industry", "beta", "exchange", "country", "marketCap", "averageVolume",
    "investmentSignal", "investmentSignalColor", "valueScore", "valueGrade",
    "timingSignal", "timingScore",
    "step1Score", "step2Score", "step3Score", "step4Score", "step5Score", "step6Score",
    "momentumGatePass", "entryTiming", "stepDetails", "resultDetail"
})
public class StockEvaluationResponse {

    /**
     * 심볼 (티커)
     */
    private String symbol;

    /**
     * 기업명
     */
    private String companyName;

    /**
     * 현재 가격
     */
    private String currentPrice;

    /**
     * <pre>
     * 주당 가치 (적정가)
     * 적정가가 너무 높은 경우 조정됨
     * </pre>
     */
    private String fairValue;

    /**
     * 계산된 주당 가치 (실제 계산값)
     */
    private String calFairValue;

    /**
     * 가격 차이 (주당가치 - 현재가격)
     */
    private String priceDifference;

    /**
     * 가격 차이 비율 (%)
     */
    private String priceGapPercent;

    /**
     * 총 점수 (100점 만점)
     */
    private double totalScore;

    /**
     * 등급 (S, A, B, C, D, F)
     */
    private String grade;

    /**
     * 투자 추천도
     */
    private String recommendation;

    /**
     * PEG
     */
    private String peg;

    /**
     * PER
     */
    private String per;

    /**
     * 섹터
     */
    private String sector;

    /**
     * 산업군
     */
    private String industry;

    /**
     * 베타
     */
    private String beta;

    /**
     * 거래소
     */
    private String exchange;

    /**
     * 국가
     */
    private String country;

    /**
     * 시가총액
     */
    private String marketCap;

    /**
     * 평균 거래량
     */
    private Long averageVolume;

    /**
     * 매수적정가 (안전마진 적용)
     */
    private String purchasePrice;

    /**
     * 목표매도가
     */
    private String sellTarget;

    /**
     * 손절매가
     */
    private String stopLossPrice;

    /**
     * Step 1 점수
     */
    private double step1Score;

    /**
     * Step 2 점수
     */
    private double step2Score;

    /**
     * Step 3 점수
     */
    private double step3Score;

    /**
     * Step 4 점수
     */
    private double step4Score;

    /**
     * Step 5 점수
     */
    private double step5Score;

    /**
     * Step 6 점수 (모멘텀/기술적 분석)
     */
    private double step6Score;

    /**
     * 모멘텀 게이트 통과 여부
     */
    @Builder.Default
    private boolean momentumGatePass = true;

    /**
     * 진입 타이밍 분석 결과
     */
    private EntryTimingAnalysis entryTiming;

    /**
     * 각 Step별 상세 평가 정보
     */
    private List<StepEvaluationDetail> stepDetails;

    /**
     * 상세 정보 (CompanySharePriceResultDetail)
     */
    private CompanySharePriceResultDetail resultDetail;

    /**
     * 52주 고점 대비 하락률 (%)
     */
    private String high52wDropPercent;

    /** 최근 5거래일 수익률 (%) — 투자 순위 점수의 단기 눌림 요소. 가격 이력 부족 시 null */
    private Double return1wPct;

    /**
     * Forward PER (애널리스트 컨센서스 기반)
     */
    private String forwardPer;

    /**
     * Forward PER 경고 메시지 (TTM PER 대비 크게 상승 시)
     */
    private String forwardPerWarning;

    /**
     * 계산 버전
     */
    @Builder.Default
    private String calVersion = "";

    // ========== 가치×타이밍 2축 분리 (2-1) ==========

    /**
     * 투자판정 (가치등급 × 타이밍 매트릭스): "매수 후보" / "관심목록" / "관망"
     */
    private String investmentSignal;

    /**
     * 투자판정 색상: "blue"(매수후보) / "yellow"(관심목록) / "gray"(관망)
     */
    private String investmentSignalColor;

    /**
     * 가치 점수 (Step1~5 펀더멘털 합을 100점 환산, 게이트 없음)
     */
    private double valueScore;

    /**
     * 가치 등급 (S/A/B/C/D/F, 펀더멘털만 반영)
     */
    private String valueGrade;

    /**
     * 타이밍 신호: "양호" / "대기" / "하락" / "관망"
     */
    private String timingSignal;

    /**
     * 타이밍 점수 (0~100, 진입 타이밍 분석 기반)
     */
    private Integer timingScore;

}
