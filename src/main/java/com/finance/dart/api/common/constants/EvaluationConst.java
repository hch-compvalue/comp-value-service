package com.finance.dart.api.common.constants;

/**
 * 평가 시스템 상수
 */
public class EvaluationConst {

    /**
     * Redis 키 버전
     */
    public static final String CAL_VALUE_VERSION = "v9";

    /**
     * 각 Step별 가중치 (총 100점)
     * Step 1: 위험 신호 확인 - 치명적 결함 체크 (감점 방식)
     * Step 2: 신뢰도 확인 - 재무 건전성 평가
     * Step 3: 밸류에이션 평가 - PEG, 과대평가 위험
     * Step 4: 영업이익 추세 - 성장 지속가능성
     * Step 5: 투자 적합성 - 매수적정가(세분화), 그레이엄(강화)
     * Step 6: 모멘텀/기술적 분석 - 이동평균, RSI, 거래량 추세
     */
    public static final int STEP1_WEIGHT = 12;  // 위험 신호 확인 (치명적 결함 필터)
    public static final int STEP2_WEIGHT = 18;  // 신뢰도 확인
    public static final int STEP3_WEIGHT = 20;  // 밸류에이션 평가
    public static final int STEP4_WEIGHT = 15;  // 영업이익 추세 확인
    public static final int STEP5_WEIGHT = 17;  // 투자 적합성
    public static final int STEP6_WEIGHT = 18;  // 모멘텀/기술적 분석

    /**
     * Step 5 서브점수 (이중 계산 해소: PEG/PSR/PBR 이진 판단 제거, Step3과 중복 방지)
     */
    public static final int STEP5_PURCHASE_PRICE = 9;  // 매수적정가 vs 현재가 (세분화)
    public static final int STEP5_GRAHAM = 8;           // 그레이엄 기준 (강화)

    /**
     * Step 설명
     */
    public static final String STEP1_DESC = "위험 신호 확인: 수익가치계산불가, 적자기업, 매출기반평가 등 치명적 결함을 체크합니다. 문제 발견 시 대폭 감점됩니다.";
    public static final String STEP2_DESC = "신뢰도 확인: PER, 순부채, 영업이익 안정성을 종합적으로 평가하여 기업의 재무 건전성을 판단합니다.";
    public static final String STEP3_DESC = "밸류에이션 평가: PEG, 가격 차이, 성장률 등을 분석하여 현재 주가가 적정한지 또는 저평가/고평가 되었는지 판단합니다.";
    public static final String STEP4_DESC = "영업이익 추세: 최근 3년간 영업이익 추세를 분석하여 성장의 지속가능성을 평가합니다.";
    public static final String STEP5_DESC = "투자 적합성: 매수적정가 대비 현재가 세분화 평가와 그레이엄 스크리닝 결과를 종합하여 최종 투자 적합성을 판단합니다.";
    public static final String STEP6_DESC = "모멘텀/기술적 분석: 이동평균선(SMA50/200), RSI, 거래량 추세를 분석하여 매수 타이밍의 적절성을 판단합니다.";

    /**
     * 음수 적정가 게이트 상한 점수 (적정가 ≤ 0일 때)
     */
    public static final double NEGATIVE_FAIR_VALUE_MAX_SCORE = 50.0;

    /**
     * PER 기준값
     */
    public static final double PER_MIN_NORMAL = 5.0;
    public static final double PER_MAX_NORMAL = 30.0;
    public static final double PER_HIGH_RISK = 40.0;

    /**
     * PEG 기준값
     */
    public static final double PEG_UNDERVALUED = 1.0;
    public static final double PEG_FAIR = 1.5;
    public static final double PEG_OVERVALUED = 2.0;
    public static final double PEG_HIGH_RISK = 2.5;

    /**
     * 성장률 기준값
     */
    public static final double GROWTH_SUSTAINABLE = 0.5;    // 50%
    public static final double GROWTH_HIGH_RISK = 0.8;      // 80%
    public static final double GROWTH_VERY_HIGH_RISK = 1.0; // 100%

    /**
     * 진입 타이밍 연동 상한 (타이밍 점수 기반 총점 상한)
     * 타이밍 점수 < 30 ("하락 구간") → 총점 상한 65점 (C등급 한도)
     * 타이밍 점수 < 50 ("대기 권장") → 총점 상한 77점 (B등급 한도)
     */
    public static final double TIMING_GATE_RED_MAX_SCORE = 65.0;
    public static final double TIMING_GATE_YELLOW_MAX_SCORE = 77.0;
    public static final int TIMING_GATE_RED_THRESHOLD = 30;
    public static final int TIMING_GATE_YELLOW_THRESHOLD = 50;

    /**
     * SMA200 하방 디스카운트 (Step6 추가 감점)
     * 현재가/SMA200 비율에 따른 감점
     */
    public static final double SMA200_SEVERE_THRESHOLD = 0.85;  // -15% 이하
    public static final double SMA200_WARNING_THRESHOLD = 0.90; // -10% 이하
    public static final double SMA200_MILD_THRESHOLD = 1.0;     // SMA200 미만
    public static final int SMA200_SEVERE_PENALTY = 5;
    public static final int SMA200_WARNING_PENALTY = 3;
    public static final int SMA200_MILD_PENALTY = 1;

    /**
     * 52주 고점 대비 하락률 감점 (Step6 추가 감점)
     */
    public static final double HIGH52W_SEVERE_DROP = -0.30;  // -30% 이상 하락
    public static final double HIGH52W_WARNING_DROP = -0.20;  // -20% 이상 하락
    public static final int HIGH52W_SEVERE_PENALTY = 4;
    public static final int HIGH52W_WARNING_PENALTY = 2;

    /**
     * Forward PER 크로스체크 (Step2 감점)
     * Forward PER / TTM PER 비율이 높으면 미래 실적 악화 예상
     */
    public static final double FORWARD_PER_SEVERE_RATIO = 1.5;  // 50% 이상 증가
    public static final double FORWARD_PER_WARNING_RATIO = 1.3; // 30% 이상 증가
    public static final int FORWARD_PER_SEVERE_PENALTY = 5;
    public static final int FORWARD_PER_WARNING_PENALTY = 3;

    /**
     * 가치×타이밍 2축 분리 (2-1)
     * 가치등급: Step1~5 펀더멘털 합(최대 82점)을 100점 환산하여 산정 (게이트/타이밍 감점 없음)
     */
    public static final int VALUE_SCORE_MAX = STEP1_WEIGHT + STEP2_WEIGHT + STEP3_WEIGHT
            + STEP4_WEIGHT + STEP5_WEIGHT;  // 82점 (펀더멘털 Step1~5 만점)

    /** 투자판정 라벨 (가치등급 × 타이밍신호 매트릭스) */
    public static final String INVEST_SIGNAL_BUY = "매수 후보";       // 가치 양호 + 타이밍 양호
    public static final String INVEST_SIGNAL_WATCH = "관심목록";      // 가치 양호 + 타이밍 미흡
    public static final String INVEST_SIGNAL_HOLD = "관망";           // 가치 미흡

    /** 투자판정 색상 (색각이상 친화: 파랑/노랑/회색) */
    public static final String INVEST_COLOR_BUY = "blue";
    public static final String INVEST_COLOR_WATCH = "yellow";
    public static final String INVEST_COLOR_HOLD = "gray";

    /** 타이밍 신호 라벨 (entryTiming.signal → 표준화) */
    public static final String TIMING_GOOD = "양호";
    public static final String TIMING_WAIT = "대기";
    public static final String TIMING_DOWN = "하락";
    public static final String TIMING_NEUTRAL = "관망";

    /** entryTiming 없을 때 step6 점수 기반 타이밍 판정 임계값 (18점 만점) */
    public static final double TIMING_FALLBACK_GOOD_STEP6 = 13.0;
    public static final double TIMING_FALLBACK_WAIT_STEP6 = 9.0;

    /**
     * 매수후보 품질 게이트 / 극단 괴리 신뢰도 하향 (Fix1/Fix3)
     * - 밸류에이션 계산의 약점(소형주/금융주/채권)이 매수후보로 새는 것 방지
     */
    public static final long QUALITY_GATE_MICROCAP = 300_000_000L;      // 초소형주 기준 $300M
    public static final double QUALITY_GATE_UNREALISTIC_GAP_PCT = 200.0; // 비현실적 괴리(계산신뢰도 낮음): 매수후보→관심목록, 가치점수 하향
    public static final double QUALITY_GATE_OUTLIER_GAP_PCT = 2000.0;    // 이상치 괴리: 관망으로 강등

    // ========== V9 적정가 수준 보정 (2026-10-13) ==========
    // V9(자기자본법+정상금리 6% 이자하한)는 적정가 수준이 V8보다 낮음(괴리 중앙값 -16% → -45%). 순위 예측력은 개선됐으므로
    // 각 점수 구간에 드는 종목 비율이 V8과 같도록 기준을 분위 매칭으로 이동 (test/backtest-v8-2026-10, 2018~2025 11,659건)
    // 괄호 = V8 기준값. 안전마진(매수적정가)은 보수적 의미 유지를 위해 보정하지 않음
    /** Step3 가격괴리 6점 구간 하한 % (V8 +30) */
    public static final double STEP3_GAP_UNDERVALUED_PCT = -8.0;
    /** Step3 가격괴리 5점 구간 하한 % (V8 +20) */
    public static final double STEP3_GAP_SLIGHT_UNDERVALUED_PCT = -14.0;
    /** Step3 가격괴리 4점 구간 하한 % (V8 +10) */
    public static final double STEP3_GAP_MILD_UNDERVALUED_PCT = -21.0;
    /** Step3 가격괴리 2점 구간 하한 % (V8 0) */
    public static final double STEP3_GAP_FAIR_PCT = -27.0;
    /** Step3 가격괴리 1점 구간 하한 % (V8 -10), 미만은 고평가 0점 */
    public static final double STEP3_GAP_SLIGHT_OVERVALUED_PCT = -36.0;

    /** Step5 그레이엄 고평가 감점 50% 시작 괴리 % (V8 -10) */
    public static final double STEP5_OVERVALUED_PENALTY_HALF_PCT = -36.0;
    /** Step5 그레이엄 고평가 감점 75% 시작 괴리 % (V8 -20) */
    public static final double STEP5_OVERVALUED_PENALTY_MOST_PCT = -42.0;
    /** Step5 그레이엄 고평가 감점 100% 시작 괴리 % (V8 -30) */
    public static final double STEP5_OVERVALUED_PENALTY_FULL_PCT = -50.0;

    /** Step5 매수적정가 미달 시 적정가 상승여력 4점 하한 % (V8 +10) */
    public static final double STEP5_FAIR_GAP_HIGH_PCT = -21.0;
    /** Step5 매수적정가 미달 시 적정가 상승여력 2점 하한 % (V8 0) */
    public static final double STEP5_FAIR_GAP_LOW_PCT = -27.0;

}
