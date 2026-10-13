package com.finance.dart.api.common.service;

import com.finance.dart.api.abroad.dto.fmp.analystestimates.AnalystEstimatesReqDto;
import com.finance.dart.api.abroad.dto.fmp.analystestimates.AnalystEstimatesResDto;
import com.finance.dart.api.abroad.dto.fmp.chart.StockPriceVolumeReqDto;
import com.finance.dart.api.abroad.dto.fmp.chart.StockPriceVolumeResDto;
import com.finance.dart.api.abroad.dto.fmp.company.CompanyProfileDataResDto;
import com.finance.dart.api.abroad.dto.fmp.quote.StockQuoteReqDto;
import com.finance.dart.api.abroad.dto.fmp.quote.StockQuoteResDto;
import com.finance.dart.api.abroad.component.FmpRateLimiter;
import com.finance.dart.api.abroad.service.US_StockCalFromFpmService;
import com.finance.dart.api.abroad.service.fmp.AnalystEstimatesService;
import com.finance.dart.api.abroad.service.fmp.CompanyProfileSearchService;
import com.finance.dart.api.abroad.service.fmp.StockPriceVolumeService;
import com.finance.dart.api.abroad.service.fmp.StockQuoteService;
import com.finance.dart.api.common.constants.EvaluationConst;
import com.finance.dart.api.common.dto.CompanySharePriceResult;
import com.finance.dart.api.common.dto.CompanySharePriceResultDetail;
import com.finance.dart.api.common.dto.evaluation.EntryTimingAnalysis;
import com.finance.dart.api.common.dto.evaluation.StepEvaluationDetail;
import com.finance.dart.api.common.dto.evaluation.StockEvaluationRequest;
import com.finance.dart.api.common.dto.evaluation.StockEvaluationResponse;
import com.finance.dart.api.common.util.SecurityTypeUtil;
import com.finance.dart.common.component.RedisComponent;
import com.finance.dart.common.component.RedisKeyGenerator;
import com.finance.dart.common.util.StringUtil;
import com.google.gson.Gson;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 종목 평가 서비스
 * Step 1~5 상세 평가를 통한 점수 산출
 */
@Slf4j
@AllArgsConstructor
@Service
public class StockEvaluationService {

    private final int MAX_EVALUATION_SYMBOLS = 50;  // 한번에 조회할 수 있는 최대 개수

    private final RedisComponent redisComponent;
    private final US_StockCalFromFpmService stockCalFromFpmService;
    private final CompanyProfileSearchService profileSearchService;
    private final TechnicalAnalysisService technicalAnalysisService;
    private final StockQuoteService stockQuoteService;
    private final StockPriceVolumeService stockPriceVolumeService;
    private final AnalystEstimatesService analystEstimatesService;
    private final FmpRateLimiter fmpRateLimiter;

    /**
     * 종목 평가 (다건)
     * @param request 평가 요청 (심볼 리스트)
     * @return 평가 결과 리스트
     */
    public List<StockEvaluationResponse> evaluateStocks(StockEvaluationRequest request) {
        List<StockEvaluationResponse> responses = new ArrayList<>();

        if (request.getSymbols() == null || request.getSymbols().isEmpty()) {
            return responses;
        }

        if(request.getSymbols().size() > MAX_EVALUATION_SYMBOLS) {
            StockEvaluationResponse errorResponse = StockEvaluationResponse.builder()
                    .symbol("")
                    .totalScore(0.0)
                    .grade("ERROR")
                    .recommendation("평가 실패: 거래 당 " + MAX_EVALUATION_SYMBOLS + "건 이하로 조회가능합니다.")
                    .build();
            responses.add(errorResponse);
            return responses;
        }

        for (String symbol : request.getSymbols()) {
            fmpRateLimiter.waitIfNeeded();  // Rate limit 종목 간 체크
            try {
                StockEvaluationResponse response = evaluateSingleStock(symbol);
                responses.add(response);
            } catch (Exception e) {
                log.error("Failed to evaluate stock: {}", symbol, e);
                // 실패한 경우에도 빈 응답 추가 (에러 정보 포함)
                StockEvaluationResponse errorResponse = StockEvaluationResponse.builder()
                        .symbol(symbol)
                        .totalScore(0.0)
                        .grade("ERROR")
                        .recommendation("평가 실패: " + e.getMessage())
                        .build();
                responses.add(errorResponse);
            }
        }

        return responses;
    }

    /**
     * 단일 종목 평가
     * @param symbol 심볼
     * @return 평가 결과
     */
    private StockEvaluationResponse evaluateSingleStock(String symbol) throws Exception {

        // 1. Redis에서 데이터 조회 시도
        String redisKey = RedisKeyGenerator.genAbroadCompValueRstData(symbol, EvaluationConst.CAL_VALUE_VERSION);
        String cachedData = redisComponent.getValue(redisKey);

        CompanySharePriceResult result;
        if (!StringUtil.isStringEmpty(cachedData)) {
            // Redis에 데이터 있음
            result = new Gson().fromJson(cachedData, CompanySharePriceResult.class);
            log.debug("Loaded from Redis: {}", symbol);
        } else {
            // Redis에 데이터 없음 -> calPerValue 최신버전 실행
            result = stockCalFromFpmService.calPerValue(symbol);
            log.debug("Calculated new value: {}", symbol);
            // Thread.sleep(1500); // FmpRateLimiter로 대체 (2026.03.10)
        }

        // 2. 기업 정보 조회
        CompanyProfileDataResDto profile = getCompanyProfile(symbol);

        // 3. Step 1~5 평가 수행
        CompanySharePriceResultDetail detail = result.get상세정보();
        if (detail == null) {
            throw new IllegalStateException(symbol + " - 상세정보 없음 (재무데이터 부족)");
        }
        String currentPrice = result.get현재가격();
        String fairValue = result.get주당가치();
        String calFairValue = result.get계산된주당가치();

        List<StepEvaluationDetail> stepDetails = new ArrayList<>();
        double step1Score = evaluateStep1(detail, stepDetails);
        double step2Score = evaluateStep2(detail, stepDetails);
        double step3Score = evaluateStep3(detail, stepDetails, currentPrice, fairValue);
        double step4Score = evaluateStep4(detail, stepDetails);
        double step5Score = evaluateStep5(detail, result, currentPrice, stepDetails);

        // 3-1. Step 6: 모멘텀/기술적 분석 (데이터 조회를 여기서 수행하여 타이밍 분석과 공유)
        StockQuoteResDto stockQuote = fetchStockQuote(symbol);
        List<StockPriceVolumeResDto> priceHistory = fetchPriceHistory(symbol);

        double step6Score = evaluateStep6(symbol, detail, stepDetails, stockQuote, priceHistory);

        // 3-2. 진입 타이밍 분석 (동일 데이터 재사용)
        EntryTimingAnalysis entryTiming = null;
        try {
            entryTiming = technicalAnalysisService.analyzeEntryTiming(stockQuote, priceHistory);
        } catch (Exception e) {
            log.warn("[EntryTiming] {} - 진입 타이밍 분석 실패: {}", symbol, e.getMessage());
        }

        // 4. 총점 계산
        double totalScore = step1Score + step2Score + step3Score + step4Score + step5Score + step6Score;

        // 4-1. 모멘텀 게이트 상태 (정보 제공용, Step6 점수로 자연 반영)
        boolean momentumGatePass = detail.is모멘텀게이트통과();

        // 4-2. 음수 적정가 게이트 적용 (적정가 ≤ 0이면 총점 상한 50점)
        if (!StringUtil.isStringEmpty(fairValue)) {
            try {
                double fv = Double.parseDouble(fairValue);
                if (fv <= 0 && totalScore > EvaluationConst.NEGATIVE_FAIR_VALUE_MAX_SCORE) {
                    totalScore = EvaluationConst.NEGATIVE_FAIR_VALUE_MAX_SCORE;
                }
            } catch (Exception ignored) {}
        }

        // 4-3. [개선1] 진입 타이밍 → 등급 상한 연동
        if (entryTiming != null) {
            int timingScore = entryTiming.getTimingScore();
            if (timingScore < EvaluationConst.TIMING_GATE_RED_THRESHOLD
                    && totalScore > EvaluationConst.TIMING_GATE_RED_MAX_SCORE) {
                log.info("[TimingGate] {} - 타이밍 점수 {}(<{}), 총점 {} → {} 상한 적용",
                        symbol, timingScore, EvaluationConst.TIMING_GATE_RED_THRESHOLD,
                        totalScore, EvaluationConst.TIMING_GATE_RED_MAX_SCORE);
                totalScore = EvaluationConst.TIMING_GATE_RED_MAX_SCORE;
            } else if (timingScore < EvaluationConst.TIMING_GATE_YELLOW_THRESHOLD
                    && totalScore > EvaluationConst.TIMING_GATE_YELLOW_MAX_SCORE) {
                log.info("[TimingGate] {} - 타이밍 점수 {}(<{}), 총점 {} → {} 상한 적용",
                        symbol, timingScore, EvaluationConst.TIMING_GATE_YELLOW_THRESHOLD,
                        totalScore, EvaluationConst.TIMING_GATE_YELLOW_MAX_SCORE);
                totalScore = EvaluationConst.TIMING_GATE_YELLOW_MAX_SCORE;
            }
        }

        // 4-4. [개선3] 52주 고점 대비 하락률 감점
        String high52wDropPercent = calculate52WeekHighDrop(priceHistory, stockQuote);
        if (high52wDropPercent != null) {
            try {
                double dropPct = Double.parseDouble(high52wDropPercent.replace("%", ""));
                double dropRatio = dropPct / 100.0;
                if (dropRatio <= EvaluationConst.HIGH52W_SEVERE_DROP) {
                    totalScore -= EvaluationConst.HIGH52W_SEVERE_PENALTY;
                    log.info("[52wHigh] {} - 52주 고점 대비 {}% 하락, -{} 감점",
                            symbol, dropPct, EvaluationConst.HIGH52W_SEVERE_PENALTY);
                } else if (dropRatio <= EvaluationConst.HIGH52W_WARNING_DROP) {
                    totalScore -= EvaluationConst.HIGH52W_WARNING_PENALTY;
                    log.info("[52wHigh] {} - 52주 고점 대비 {}% 하락, -{} 감점",
                            symbol, dropPct, EvaluationConst.HIGH52W_WARNING_PENALTY);
                }
                totalScore = Math.max(0, totalScore);
            } catch (Exception ignored) {}
        }

        // 4-5. [개선4] Forward PER 크로스체크
        String forwardPer = null;
        String forwardPerWarning = null;
        try {
            AnalystEstimatesResDto estimates = fetchAnalystEstimates(symbol);
            if (estimates != null && estimates.getEstimatedEpsAvg() != null
                    && estimates.getEstimatedEpsAvg() > 0
                    && stockQuote != null && stockQuote.getPrice() != null) {
                double fwdPer = stockQuote.getPrice() / estimates.getEstimatedEpsAvg();
                forwardPer = String.format("%.2f", fwdPer);

                String perStr = detail.getPER();
                if (!StringUtil.isStringEmpty(perStr) && !"N/A".equals(perStr)) {
                    double ttmPer = Double.parseDouble(perStr);
                    if (ttmPer > 0) {
                        double ratio = fwdPer / ttmPer;
                        if (ratio >= EvaluationConst.FORWARD_PER_SEVERE_RATIO) {
                            totalScore -= EvaluationConst.FORWARD_PER_SEVERE_PENALTY;
                            forwardPerWarning = String.format(
                                    "Forward PER(%.1f)이 TTM PER(%.1f) 대비 %.0f%% 높음 - 실적 악화 예상, -%d점",
                                    fwdPer, ttmPer, (ratio - 1) * 100, EvaluationConst.FORWARD_PER_SEVERE_PENALTY);
                            log.info("[ForwardPER] {} - {}", symbol, forwardPerWarning);
                        } else if (ratio >= EvaluationConst.FORWARD_PER_WARNING_RATIO) {
                            totalScore -= EvaluationConst.FORWARD_PER_WARNING_PENALTY;
                            forwardPerWarning = String.format(
                                    "Forward PER(%.1f)이 TTM PER(%.1f) 대비 %.0f%% 높음 - 실적 둔화 우려, -%d점",
                                    fwdPer, ttmPer, (ratio - 1) * 100, EvaluationConst.FORWARD_PER_WARNING_PENALTY);
                            log.info("[ForwardPER] {} - {}", symbol, forwardPerWarning);
                        }
                        totalScore = Math.max(0, totalScore);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[ForwardPER] {} - 애널리스트 추정치 조회 실패: {}", symbol, e.getMessage());
        }

        // 5. 가격 차이 계산
        String priceDifference = calculatePriceDifference(currentPrice, fairValue);
        String priceGapPercent = calculatePriceGapPercent(currentPrice, fairValue);

        // 6. 등급 및 추천도 산정 (기존 게이트 적용 총점 기반 - 하위호환 유지)
        String grade = calculateGrade(totalScore);
        String recommendation = generateRecommendation(totalScore, detail);

        // 6-1. [2-1] 가치×타이밍 2축 분리
        //  - 가치등급: Step1~5 펀더멘털 합(82점)을 100점 환산 (게이트/타이밍 감점 없음)
        //  - 타이밍신호: 진입 타이밍 분석(entryTiming) → 표준 라벨, 없으면 step6 점수로 폴백
        //  - 투자판정: 가치등급 × 타이밍 매트릭스
        double valueScore = calculateValueScore(step1Score, step2Score, step3Score, step4Score, step5Score);
        String valueGrade = calculateGrade(valueScore);
        String timingSignal = resolveTimingSignal(entryTiming, step6Score);
        Integer timingScore = (entryTiming != null) ? entryTiming.getTimingScore() : null;
        String investmentSignal = resolveInvestmentSignal(valueGrade, timingSignal);

        // 6-2. [Fix1] 매수후보 품질 게이트 - 밸류에이션 계산 약점(초소형주/극단괴리/채권)이 매수후보로 새는 것 방지
        Long marketCap = (profile != null) ? profile.getMarketCap() : null;
        String gatedSignal = applyQualityGate(investmentSignal, marketCap, priceGapPercent, symbol, result.get기업명());
        if (!gatedSignal.equals(investmentSignal)) {
            log.info("[QualityGate] {} - 투자판정 {} → {} (시총 {}, 괴리 {})",
                    symbol, investmentSignal, gatedSignal, marketCap, priceGapPercent);
            investmentSignal = gatedSignal;
        }
        String investmentSignalColor = resolveInvestmentSignalColor(investmentSignal);

        // 7. 응답 DTO 생성
        return StockEvaluationResponse.builder()
                .symbol(symbol)
                .companyName(result.get기업명())
                .currentPrice(currentPrice)
                .fairValue(fairValue)
                .calFairValue(calFairValue)
                .priceDifference(priceDifference)
                .priceGapPercent(priceGapPercent)
                .totalScore(totalScore)
                .grade(grade)
                .recommendation(recommendation)
                .purchasePrice(result.get매수적정가())
                .sellTarget(result.get목표매도가())
                .stopLossPrice(result.get손절매가())
                .peg(detail.getPEG())
                .per(detail.getPER())
                .sector(profile != null ? profile.getSector() : "N/A")
                .industry(profile != null ? profile.getIndustry() : "N/A")
                .beta(profile != null && profile.getBeta() != null ? profile.getBeta().toString() : "N/A")
                .exchange(profile != null ? profile.getExchange() : "N/A")
                .country(profile != null ? profile.getCountry() : "N/A")
                .marketCap(profile != null && profile.getMarketCap() != null ?
                        formatMarketCap(profile.getMarketCap()) : "N/A")
                .averageVolume(profile != null ? profile.getAverageVolume() : null)
                .step1Score(step1Score)
                .step2Score(step2Score)
                .step3Score(step3Score)
                .step4Score(step4Score)
                .step5Score(step5Score)
                .step6Score(step6Score)
                .momentumGatePass(momentumGatePass)
                .entryTiming(entryTiming)
                .high52wDropPercent(high52wDropPercent)
                .forwardPer(forwardPer)
                .forwardPerWarning(forwardPerWarning)
                .valueScore(valueScore)
                .valueGrade(valueGrade)
                .timingSignal(timingSignal)
                .timingScore(timingScore)
                .investmentSignal(investmentSignal)
                .investmentSignalColor(investmentSignalColor)
                .stepDetails(stepDetails)
                .resultDetail(detail)
                .calVersion(result.get버전())
                .build();
    }

    /**
     * Step 1: 위험 신호 확인 (12점) - 치명적 결함 필터
     * - 수익가치계산불가: 0점
     * - 적자기업 + 매출기반평가: 5점
     * - 적자기업: 6점
     * - 매출기반평가: 7점
     * - 정상 기업: 12점
     */
    private double evaluateStep1(CompanySharePriceResultDetail detail, List<StepEvaluationDetail> stepDetails) {
        double score = EvaluationConst.STEP1_WEIGHT;
        StringBuilder details = new StringBuilder();

        if (detail.is수익가치계산불가()) {
            score = 0;
            details.append("❌ 수익가치 계산 불가 (적정가 신뢰도 매우 낮음, 0점). ");
        } else if (detail.is적자기업() && detail.is매출기반평가()) {
            score = 5;
            details.append("⚠️ 적자기업이며 매출 기반 평가 (리스크 높음, ")
                    .append(String.format("%.1f", score)).append("점). ");
        } else if (detail.is적자기업()) {
            score = 6;
            details.append("⚠️ 적자기업 (투자 위험, ")
                    .append(String.format("%.1f", score)).append("점). ");
        } else if (detail.is매출기반평가()) {
            score = 7;
            details.append("⚠️ 매출 기반 평가 (이익 없이 매출만 큰 기업, 과대평가 가능성, ")
                    .append(String.format("%.1f", score)).append("점). ");
        } else {
            details.append("✅ 정상 기업 (PER 기반 정상 계산, 만점 ")
                    .append(EvaluationConst.STEP1_WEIGHT).append("점). ");
        }

        if (detail.is흑자전환기업()) {
            details.append("📈 흑자 전환 기업 (긍정적 신호). ");
        }

        stepDetails.add(StepEvaluationDetail.builder()
                .stepNumber(1)
                .stepName("위험 신호 확인")
                .score(score)
                .maxScore(EvaluationConst.STEP1_WEIGHT)
                .description(EvaluationConst.STEP1_DESC)
                .details(details.toString())
                .build());

        return score;
    }

    /**
     * Step 2: 신뢰도 확인 (18점)
     * - PER 정상 범위 (5~30): +6점
     * - 순부채 건전 (음수 또는 낮음): +7점
     * - 영업이익 안정성: +5점
     */
    private double evaluateStep2(CompanySharePriceResultDetail detail, List<StepEvaluationDetail> stepDetails) {
        double score = 0;
        StringBuilder details = new StringBuilder();

        // PER 평가 (6점)
        String perStr = detail.getPER();
        if (!StringUtil.isStringEmpty(perStr) && !"N/A".equals(perStr)) {
            try {
                double per = Double.parseDouble(perStr);
                if (per >= EvaluationConst.PER_MIN_NORMAL && per <= EvaluationConst.PER_MAX_NORMAL) {
                    score += 6;
                    details.append(String.format("✅ PER %.2f (정상 범위 5~30, +6점). ", per));
                } else if (per < EvaluationConst.PER_HIGH_RISK) {
                    score += 3;
                    details.append(String.format("⚠️ PER %.2f (보통, +3점). ", per));
                } else {
                    score += 1;
                    details.append(String.format("❌ PER %.2f (고평가 가능성, +1점). ", per));
                }
            } catch (Exception e) {
                details.append("PER 정보 없음 (+0점). ");
            }
        } else {
            details.append("PER 정보 없음 (+0점). ");
        }

        // 순부채 평가 (7점)
        String netDebtStr = detail.get순부채();
        if (!StringUtil.isStringEmpty(netDebtStr) && !"N/A".equals(netDebtStr)) {
            try {
                double netDebt = Double.parseDouble(netDebtStr);
                if (netDebt < 0) {
                    score += 7;
                    details.append("✅ 순부채 음수 (현금이 부채보다 많음, 매우 건전, +7점). ");
                } else if (netDebt < 100000000000.0) {  // 1000억 미만
                    score += 4;
                    details.append("✅ 순부채 건전 (+4점). ");
                } else {
                    score += 2;
                    details.append("⚠️ 순부채 높음 (+2점). ");
                }
            } catch (Exception e) {
                details.append("순부채 정보 없음 (+0점). ");
            }
        } else {
            details.append("순부채 정보 없음 (+0점). ");
        }

        // 영업이익 안정성 (5점)
        String op1 = detail.get영업이익_전전기();
        String op2 = detail.get영업이익_전기();
        String op3 = detail.get영업이익_당기();

        if (!StringUtil.isStringEmpty(op1) && !StringUtil.isStringEmpty(op2) && !StringUtil.isStringEmpty(op3)
                && !"N/A".equals(op1) && !"N/A".equals(op2) && !"N/A".equals(op3)) {
            try {
                double o1 = Double.parseDouble(op1);
                double o2 = Double.parseDouble(op2);
                double o3 = Double.parseDouble(op3);

                if (o1 > 0 && o2 > 0 && o3 > 0 && o2 >= o1 && o3 >= o2) {
                    score += 5;
                    details.append("✅ 영업이익 3년 연속 흑자 및 증가 추세 (+5점). ");
                } else if (o1 > 0 && o2 > 0 && o3 > 0) {
                    score += 3;
                    details.append("✅ 영업이익 3년 연속 흑자 (+3점). ");
                } else {
                    score += 1;
                    details.append("⚠️ 영업이익 불안정 (+1점). ");
                }
            } catch (Exception e) {
                details.append("영업이익 정보 오류 (+0점). ");
            }
        } else {
            details.append("영업이익 정보 없음 (+0점). ");
        }

        stepDetails.add(StepEvaluationDetail.builder()
                .stepNumber(2)
                .stepName("신뢰도 확인")
                .score(score)
                .maxScore(EvaluationConst.STEP2_WEIGHT)
                .description(EvaluationConst.STEP2_DESC)
                .details(details.toString())
                .build());

        return score;
    }

    /**
     * Step 3: 밸류에이션 평가 (20점)
     * - PEG 평가: 최대 8점
     * - 가격 차이(저평가 여부): 최대 6점 (V8: 과신 방지)
     * - 성장률 지속가능성: 최대 6점
     */
    private double evaluateStep3(CompanySharePriceResultDetail detail, List<StepEvaluationDetail> stepDetails,
                                  String currentPrice, String fairValue) {
        double score = 0;
        StringBuilder details = new StringBuilder();

        // 1. PEG 평가 (8점) — PBR 기반 평가 시 PBR-relative로 대체
        if (detail.isPBR기반평가()) {
            // PBR-relative 스코어링
            String pbrStr = detail.getPBR();
            String targetPbrStr = detail.getTargetPBR();
            if (!StringUtil.isStringEmpty(pbrStr) && !"N/A".equals(pbrStr)
                    && !StringUtil.isStringEmpty(targetPbrStr) && !"N/A".equals(targetPbrStr)) {
                try {
                    double pbr = Double.parseDouble(pbrStr);
                    double targetPbr = Double.parseDouble(targetPbrStr);
                    if (targetPbr > 0) {
                        double ratio = pbr / targetPbr;
                        // 금융주 PBR 임계값 강화: 은행의 낮은 PBR은 업계 정상이므로 기준을 엄격하게 적용
                        if (ratio < 0.3) {
                            score += 8;
                            details.append(String.format("🌟 PBR/targetPBR %.2f (매우 저평가, +8점). ", ratio));
                        } else if (ratio < 0.5) {
                            score += 7;
                            details.append(String.format("✅ PBR/targetPBR %.2f (저평가, +7점). ", ratio));
                        } else if (ratio < 0.7) {
                            score += 5;
                            details.append(String.format("✅ PBR/targetPBR %.2f (양호, +5점). ", ratio));
                        } else if (ratio < 0.85) {
                            score += 3;
                            details.append(String.format("⚠️ PBR/targetPBR %.2f (적정, +3점). ", ratio));
                        } else if (ratio < 1.0) {
                            score += 2;
                            details.append(String.format("⚠️ PBR/targetPBR %.2f (보통, +2점). ", ratio));
                        } else if (ratio < 1.2) {
                            score += 1;
                            details.append(String.format("⚠️ PBR/targetPBR %.2f (고평가 위험, +1점). ", ratio));
                        } else {
                            details.append(String.format("❌ PBR/targetPBR %.2f (과대평가, +0점). ", ratio));
                        }
                    } else {
                        details.append("targetPBR 정보 오류 (+0점). ");
                    }
                } catch (Exception e) {
                    details.append("PBR 정보 오류 (+0점). ");
                }
            } else {
                details.append("PBR/targetPBR 정보 없음 (+0점). ");
            }
        } else {
            // 기존 PEG 스코어링
            String pegStr = detail.getPEG();
            if (!StringUtil.isStringEmpty(pegStr) && !"N/A".equals(pegStr) && !"999".equals(pegStr)) {
                try {
                    double peg = Double.parseDouble(pegStr);
                    if (peg < 0.5) {
                        score += 8;
                        details.append(String.format("🌟 PEG %.2f (매우 저평가, +8점). ", peg));
                    } else if (peg < 0.8) {
                        score += 7;
                        details.append(String.format("✅ PEG %.2f (저평가, +7점). ", peg));
                    } else if (peg < 1.0) {
                        score += 5;
                        details.append(String.format("✅ PEG %.2f (양호, +5점). ", peg));
                    } else if (peg < 1.2) {
                        score += 3;
                        details.append(String.format("⚠️ PEG %.2f (적정, +3점). ", peg));
                    } else if (peg < 1.5) {
                        score += 2;
                        details.append(String.format("⚠️ PEG %.2f (보통, +2점). ", peg));
                    } else if (peg < 2.0) {
                        score += 1;
                        details.append(String.format("⚠️ PEG %.2f (고평가 위험, +1점). ", peg));
                    } else {
                        score += 0;
                        details.append(String.format("❌ PEG %.2f (과대평가, +0점). ", peg));
                    }
                } catch (Exception e) {
                    details.append("PEG 정보 오류 (+0점). ");
                }
            } else {
                details.append("PEG 정보 없음 (+0점). ");
            }
        }

        // 2. 가격 차이 평가 (6점)
        if (!StringUtil.isStringEmpty(currentPrice) && !StringUtil.isStringEmpty(fairValue)) {
            try {
                BigDecimal current = new BigDecimal(currentPrice);
                BigDecimal fair = new BigDecimal(fairValue);

                if (current.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal diff = fair.subtract(current);
                    BigDecimal gapPercent = diff.divide(current, 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100"));
                    double gap = gapPercent.doubleValue();

                    // [Fix3] 비현실적 괴리(적정가가 현재가의 3배 이상 등)는 저평가가 아니라 계산오류 신호 → 만점 대신 최소
                    if (Math.abs(gap) >= EvaluationConst.QUALITY_GATE_UNREALISTIC_GAP_PCT) {
                        score += 1;
                        details.append(String.format("⚠️ 가격차이 %.1f%% (비현실적 괴리 - 계산 신뢰도 낮음, +1점). ", gap));
                    // V9 적정가는 보수적이라 괴리가 음수여도 상대적으로 저평가일 수 있음 → '구간' 표현 + 기준 병기
                    } else if (gap >= EvaluationConst.STEP3_GAP_UNDERVALUED_PCT) {
                        score += 6;
                        details.append(String.format("✅ 가격차이 %.1f%% (저평가 구간 ≥%.0f%%, +6점). ", gap, EvaluationConst.STEP3_GAP_UNDERVALUED_PCT));
                    } else if (gap >= EvaluationConst.STEP3_GAP_SLIGHT_UNDERVALUED_PCT) {
                        score += 5;
                        details.append(String.format("✅ 가격차이 %.1f%% (약간 저평가 구간 ≥%.0f%%, +5점). ", gap, EvaluationConst.STEP3_GAP_SLIGHT_UNDERVALUED_PCT));
                    } else if (gap >= EvaluationConst.STEP3_GAP_MILD_UNDERVALUED_PCT) {
                        score += 4;
                        details.append(String.format("✅ 가격차이 %.1f%% (소폭 저평가 구간 ≥%.0f%%, +4점). ", gap, EvaluationConst.STEP3_GAP_MILD_UNDERVALUED_PCT));
                    } else if (gap >= EvaluationConst.STEP3_GAP_FAIR_PCT) {
                        score += 2;
                        details.append(String.format("⚠️ 가격차이 %.1f%% (적정가 수준 구간 ≥%.0f%%, +2점). ", gap, EvaluationConst.STEP3_GAP_FAIR_PCT));
                    } else if (gap >= EvaluationConst.STEP3_GAP_SLIGHT_OVERVALUED_PCT) {
                        score += 1;
                        details.append(String.format("⚠️ 가격차이 %.1f%% (약간 고평가 구간 ≥%.0f%%, +1점). ", gap, EvaluationConst.STEP3_GAP_SLIGHT_OVERVALUED_PCT));
                    } else {
                        score += 0;
                        details.append(String.format("❌ 가격차이 %.1f%% (고평가, +0점). ", gap));
                    }
                }
            } catch (Exception e) {
                details.append("가격 차이 계산 오류 (+0점). ");
            }
        } else {
            details.append("가격 정보 없음 (+0점). ");
        }

        // 3. 성장률 지속가능성 평가 (6점)
        String growthStr = detail.get영업이익성장률();
        if (!StringUtil.isStringEmpty(growthStr) && !"N/A".equals(growthStr)) {
            try {
                double growth = Double.parseDouble(growthStr);
                double growthPct = growth * 100;
                if (growth < 0) {
                    score += 1;
                    details.append(String.format("❌ 영업이익 성장률 %.1f%% (역성장, +1점). ", growthPct));
                } else if (growthPct <= 5) {
                    score += 2;
                    details.append(String.format("⚠️ 영업이익 성장률 %.1f%% (저성장, +2점). ", growthPct));
                } else if (growthPct <= 15) {
                    score += 4;
                    details.append(String.format("✅ 영업이익 성장률 %.1f%% (안정 성장, +4점). ", growthPct));
                } else if (growthPct <= 80) {
                    score += 6;
                    details.append(String.format("🌟 영업이익 성장률 %.1f%% (고성장, +6점). ", growthPct));
                } else {
                    score += 5;
                    details.append(String.format("🌟 영업이익 성장률 %.1f%% (초고성장, 적정가에서 지속가능성 할인 반영됨, +5점). ", growthPct));
                }
            } catch (Exception e) {
                details.append("성장률 정보 오류 (+0점). ");
            }
        } else {
            details.append("성장률 정보 없음 (+0점). ");
        }

        stepDetails.add(StepEvaluationDetail.builder()
                .stepNumber(3)
                .stepName("밸류에이션 평가")
                .score(score)
                .maxScore(EvaluationConst.STEP3_WEIGHT)
                .description(EvaluationConst.STEP3_DESC)
                .details(details.toString())
                .build());

        return score;
    }

    /**
     * Step 4: 영업이익 추세 확인 (15점)
     * - 3년간 영업이익 추세 분석
     */
    private double evaluateStep4(CompanySharePriceResultDetail detail, List<StepEvaluationDetail> stepDetails) {
        double score = 0;
        StringBuilder details = new StringBuilder();

        String op1 = detail.get영업이익_전전기();
        String op2 = detail.get영업이익_전기();
        String op3 = detail.get영업이익_당기();

        if (!StringUtil.isStringEmpty(op1) && !StringUtil.isStringEmpty(op2) && !StringUtil.isStringEmpty(op3)
                && !"N/A".equals(op1) && !"N/A".equals(op2) && !"N/A".equals(op3)) {
            try {
                double o1 = Double.parseDouble(op1);
                double o2 = Double.parseDouble(op2);
                double o3 = Double.parseDouble(op3);

                // 당기 실적 급증 감지 (전전기·전기 정체 후 당기 2.5배 이상)
                if (o3 > o2 * 2.5 && o2 <= o1 * 1.2 && o1 > 0) {
                    score += 9;
                    details.append(String.format("⚠️ 당기 실적 급증 (전전기: %.0f억, 전기: %.0f억, 당기: %.0f억). " +
                            "일회성 가능성 있으나 흑자 유지 (+9점). ",
                            o1 / 100000000, o2 / 100000000, o3 / 100000000));
                } else if (o1 > 0 && o2 >= o1 && o3 >= o2) {
                    // 꾸준한 증가 추세 - 성장률 크기에 따라 차등
                    double avgGrowth = ((o3 / o1) - 1) / 2;  // 2년 평균 성장률
                    double avgGrowthPct = avgGrowth * 100;
                    if (avgGrowthPct >= 15) {
                        score += 15;
                    } else if (avgGrowthPct >= 5) {
                        score += 12;
                    } else {
                        score += 10;
                    }
                    details.append(String.format("✅ 영업이익 꾸준한 증가 추세 (전전기: %.0f억, 전기: %.0f억, 당기: %.0f억, " +
                            "2년 평균 성장률: %.1f%%, +%.0f점). ",
                            o1 / 100000000, o2 / 100000000, o3 / 100000000, avgGrowthPct, score));
                } else if (o1 > 0 && o2 > 0 && o3 > 0) {
                    // 흑자이지만 등락
                    score += 9;
                    details.append(String.format("✅ 영업이익 흑자 유지하나 등락 있음 (전전기: %.0f억, 전기: %.0f억, 당기: %.0f억, +9점). ",
                            o1 / 100000000, o2 / 100000000, o3 / 100000000));
                } else if (o3 > 0) {
                    // 최근 흑자 전환
                    score += 6;
                    details.append(String.format("⚠️ 최근 흑자 전환 (전전기: %.0f억, 전기: %.0f억, 당기: %.0f억, +6점). ",
                            o1 / 100000000, o2 / 100000000, o3 / 100000000));
                } else {
                    score += 1;
                    details.append("❌ 영업이익 적자 지속 (+1점). ");
                }
            } catch (Exception e) {
                details.append("영업이익 추세 분석 오류 (+0점). ");
            }
        } else {
            details.append("영업이익 데이터 부족 (+0점). ");
        }

        stepDetails.add(StepEvaluationDetail.builder()
                .stepNumber(4)
                .stepName("영업이익 추세 확인")
                .score(score)
                .maxScore(EvaluationConst.STEP4_WEIGHT)
                .description(EvaluationConst.STEP4_DESC)
                .details(details.toString())
                .build());

        return score;
    }

    /**
     * Step 5: 투자 적합성 (17점)
     * - 매수적정가 vs 현재가: 최대 9점 (세분화)
     * - 그레이엄 기준: 최대 8점 (강화)
     * ※ PEG/PSR/PBR 이진 판단 제거 (Step3과 이중 계산 해소)
     */
    private double evaluateStep5(CompanySharePriceResultDetail detail, CompanySharePriceResult result,
                                  String currentPrice, List<StepEvaluationDetail> stepDetails) {
        double score = 0;
        StringBuilder details = new StringBuilder();

        // 과대평가 단계적 감점: 적정가 대비 고평가 비율에 따라 그레이엄 점수 감산 (구간은 EvaluationConst V9 보정값)
        double overvaluedPenalty = 0.0;  // 0.0=감점없음, 1.0=전액차단
        String fairValueStr = result.get주당가치();
        if (!StringUtil.isStringEmpty(fairValueStr) && !StringUtil.isStringEmpty(currentPrice)) {
            try {
                double fair = Double.parseDouble(fairValueStr);
                double current = Double.parseDouble(currentPrice);
                if (current > 0) {
                    if (fair <= 0) {
                        overvaluedPenalty = 1.0;
                    } else {
                        double gapPct = (fair - current) / current * 100;
                        if (gapPct < EvaluationConst.STEP5_OVERVALUED_PENALTY_FULL_PCT) {
                            overvaluedPenalty = 1.0;
                        } else if (gapPct < EvaluationConst.STEP5_OVERVALUED_PENALTY_MOST_PCT) {
                            overvaluedPenalty = 0.75;
                        } else if (gapPct < EvaluationConst.STEP5_OVERVALUED_PENALTY_HALF_PCT) {
                            overvaluedPenalty = 0.5;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // 1. 매수적정가 vs 현재가 (9점) - 세분화된 평가
        String purchasePriceStr = result.get매수적정가();
        if (!StringUtil.isStringEmpty(purchasePriceStr) && !StringUtil.isStringEmpty(currentPrice)) {
            try {
                double purchasePrice = Double.parseDouble(purchasePriceStr);
                double current = Double.parseDouble(currentPrice);
                double fair = !StringUtil.isStringEmpty(fairValueStr) ? Double.parseDouble(fairValueStr) : 0;

                if (purchasePrice > current) {
                    double purchaseGapPct = (purchasePrice - current) / current * 100;
                    if (purchaseGapPct >= 20) {
                        score += EvaluationConst.STEP5_PURCHASE_PRICE;  // 9점
                        details.append(String.format("🌟 매수적정가 $%.2f > 현재가 $%.2f (%.1f%% 여유, 매수 매우 적합, +%d점). ",
                                purchasePrice, current, purchaseGapPct, EvaluationConst.STEP5_PURCHASE_PRICE));
                    } else {
                        score += 7;
                        details.append(String.format("✅ 매수적정가 $%.2f > 현재가 $%.2f (매수 적합, +7점). ",
                                purchasePrice, current));
                    }
                } else if (fair > 0 && current > 0 && (fair - current) / current * 100 >= EvaluationConst.STEP5_FAIR_GAP_LOW_PCT) {
                    double fairGapPct = (fair - current) / current * 100;
                    if (fairGapPct >= EvaluationConst.STEP5_FAIR_GAP_HIGH_PCT) {
                        score += 4;
                        details.append(String.format("✅ 적정가 $%.2f / 현재가 $%.2f (괴리 %.1f%%, 상대 상승여력 구간, +4점). ",
                                fair, current, fairGapPct));
                    } else {
                        score += 2;
                        details.append(String.format("⚠️ 적정가 $%.2f / 현재가 $%.2f (괴리 %.1f%%, 적정가 수준 구간, +2점). ",
                                fair, current, fairGapPct));
                    }
                } else {
                    details.append(String.format("❌ 매수적정가 $%.2f ≤ 현재가 $%.2f (+0점). ",
                            purchasePrice, current));
                }
            } catch (Exception e) {
                details.append("매수적정가 정보 오류 (+0점). ");
            }
        } else {
            details.append("매수적정가 정보 없음 (+0점). ");
        }

        // 2. 그레이엄 기준 (8점) - 고평가 시 단계적 감점
        int grahamPassCount = detail.get그레이엄_통과수();
        {
            int rawGrahamScore = 0;
            if (grahamPassCount >= 5) {
                rawGrahamScore = EvaluationConst.STEP5_GRAHAM;  // 8점
            } else if (grahamPassCount >= 4) {
                rawGrahamScore = 5;
            } else if (grahamPassCount >= 3) {
                rawGrahamScore = 2;
            }
            if (rawGrahamScore > 0) {
                int adjusted = (int) Math.round(rawGrahamScore * (1.0 - overvaluedPenalty));
                score += adjusted;
                if (overvaluedPenalty > 0) {
                    details.append(String.format("⚠️ 그레이엄 %d/5 통과하나 고평가 감점 %d%% 적용 (+%d점). ",
                            grahamPassCount, (int)(overvaluedPenalty * 100), adjusted));
                } else {
                    String icon = grahamPassCount >= 5 ? "🌟" : (grahamPassCount >= 4 ? "✅" : "⚠️");
                    details.append(icon + " 그레이엄 " + grahamPassCount + "/5 통과 (+" + adjusted + "점). ");
                }
            } else {
                details.append("❌ 그레이엄 " + grahamPassCount + "/5 통과 (+0점). ");
            }
        }

        // Step 5 캡 적용 (배점 정합성 보장)
        score = Math.min(score, EvaluationConst.STEP5_WEIGHT);

        stepDetails.add(StepEvaluationDetail.builder()
                .stepNumber(5)
                .stepName("투자 적합성")
                .score(score)
                .maxScore(EvaluationConst.STEP5_WEIGHT)
                .description(EvaluationConst.STEP5_DESC)
                .details(details.toString())
                .build());

        return score;
    }

    /**
     * Step 6: 모멘텀/기술적 분석 (18점)
     * - 이동평균선 분석 (MA): 최대 6점
     * - RSI 14일: 최대 5점
     * - 거래량 추세: 최대 4점
     * - (+3점 여유 = 게이트 통과 보너스)
     * - 하드 게이트: 데스크로스+주가<SMA200, RSI<20+주가<SMA50 → 총점 45점 상한
     */
    private double evaluateStep6(String symbol, CompanySharePriceResultDetail detail,
                                  List<StepEvaluationDetail> stepDetails,
                                  StockQuoteResDto stockQuote,
                                  List<StockPriceVolumeResDto> priceHistory) {
        double score = 0;
        StringBuilder detailsStr = new StringBuilder();

        try {
            // 기술적 분석 수행
            TechnicalAnalysisService.TechnicalAnalysisResult result =
                    technicalAnalysisService.analyze(stockQuote, priceHistory, detail);

            int maScore = result.getMaScore();
            int rsiScore = result.getRsiScore();
            int volumeScore = result.getVolumeScore();
            boolean gatePass = result.isGatePass();

            score = maScore + rsiScore + volumeScore;

            // 게이트 통과 보너스 (+3점)
            if (gatePass) {
                score += 3;
            }

            // 최대 18점 캡
            score = Math.min(score, EvaluationConst.STEP6_WEIGHT);

            detailsStr.append(String.format("MA 점수: %d/6, RSI 점수: %d/5, 거래량 점수: %d/4. ", maScore, rsiScore, volumeScore));
            if (!gatePass) {
                detailsStr.append("⚠️ 모멘텀 약세 신호 (").append(result.getGateReason()).append("). ");
            } else {
                detailsStr.append("✅ 모멘텀 게이트 통과 (+3점 보너스). ");
            }

            // [개선2] SMA200 하방 디스카운트
            if (stockQuote != null && stockQuote.getPrice() != null && stockQuote.getPriceAvg200() != null) {
                double price = stockQuote.getPrice();
                double sma200 = stockQuote.getPriceAvg200();
                if (sma200 > 0) {
                    double priceToSma200 = price / sma200;
                    if (priceToSma200 < EvaluationConst.SMA200_SEVERE_THRESHOLD) {
                        score -= EvaluationConst.SMA200_SEVERE_PENALTY;
                        detailsStr.append(String.format("🔻 현재가가 SMA200 대비 %.1f%% 하방 (-%d점). ",
                                (priceToSma200 - 1) * 100, EvaluationConst.SMA200_SEVERE_PENALTY));
                    } else if (priceToSma200 < EvaluationConst.SMA200_WARNING_THRESHOLD) {
                        score -= EvaluationConst.SMA200_WARNING_PENALTY;
                        detailsStr.append(String.format("⚠️ 현재가가 SMA200 대비 %.1f%% 하방 (-%d점). ",
                                (priceToSma200 - 1) * 100, EvaluationConst.SMA200_WARNING_PENALTY));
                    } else if (priceToSma200 < EvaluationConst.SMA200_MILD_THRESHOLD) {
                        score -= EvaluationConst.SMA200_MILD_PENALTY;
                        detailsStr.append(String.format("⚠️ 현재가가 SMA200 미만 (%.1f%%, -%d점). ",
                                (priceToSma200 - 1) * 100, EvaluationConst.SMA200_MILD_PENALTY));
                    }
                    score = Math.max(0, score);
                }
            }

        } catch (Exception e) {
            log.warn("[Step6] {} - 기술적 분석 실패: {}", symbol, e.getMessage());
            score = 9;  // 실패 시 중간 점수
            detailsStr.append("기술적 분석 데이터 조회 실패 (중립 처리). ");
        }

        stepDetails.add(StepEvaluationDetail.builder()
                .stepNumber(6)
                .stepName("모멘텀/기술적 분석")
                .score(score)
                .maxScore(EvaluationConst.STEP6_WEIGHT)
                .description(EvaluationConst.STEP6_DESC)
                .details(detailsStr.toString())
                .build());

        return score;
    }

    /**
     * StockQuote 조회 (SMA50, SMA200 포함)
     */
    private StockQuoteResDto fetchStockQuote(String symbol) {
        try {
            StockQuoteReqDto quoteReq = new StockQuoteReqDto(symbol);
            List<StockQuoteResDto> quotes = stockQuoteService.findStockQuote(quoteReq);
            if (quotes != null && !quotes.isEmpty()) {
                return quotes.get(0);
            }
        } catch (Exception e) {
            log.warn("[Step6] {} - StockQuote 조회 실패: {}", symbol, e.getMessage());
        }
        return null;
    }

    /**
     * 52주 가격 히스토리 조회 (RSI, 거래량, 진입 타이밍 분석용)
     */
    private List<StockPriceVolumeResDto> fetchPriceHistory(String symbol) {
        try {
            LocalDate today = LocalDate.now();
            LocalDate oneYearAgo = today.minusYears(1);
            StockPriceVolumeReqDto priceReqDto = new StockPriceVolumeReqDto(
                    symbol,
                    oneYearAgo.format(DateTimeFormatter.ISO_LOCAL_DATE),
                    today.format(DateTimeFormatter.ISO_LOCAL_DATE)
            );
            return stockPriceVolumeService.findStockPriceVolume(priceReqDto);
        } catch (Exception e) {
            log.warn("[Step6] {} - 가격 히스토리 조회 실패: {}", symbol, e.getMessage());
        }
        return null;
    }

    /**
     * [2-1] 가치 점수 계산 (Step1~5 펀더멘털 합을 100점 환산)
     * - 게이트/타이밍 감점을 반영하지 않는 순수 펀더멘털 점수
     * @return 0~100 환산 점수
     */
    static double calculateValueScore(double step1, double step2, double step3, double step4, double step5) {
        double sum = step1 + step2 + step3 + step4 + step5;
        double normalized = sum / EvaluationConst.VALUE_SCORE_MAX * 100.0;
        return Math.max(0.0, Math.min(100.0, normalized));
    }

    /**
     * [2-1] 타이밍 신호 표준화
     * - 진입 타이밍 분석(entryTiming.signal) 우선, 없으면 step6 점수로 폴백
     * @return "양호" / "대기" / "하락" / "관망"
     */
    static String resolveTimingSignal(EntryTimingAnalysis entryTiming, double step6Score) {
        if (entryTiming != null && entryTiming.getSignal() != null) {
            switch (entryTiming.getSignal()) {
                case "매수 적기": return EvaluationConst.TIMING_GOOD;
                case "대기 권장": return EvaluationConst.TIMING_WAIT;
                case "하락 구간": return EvaluationConst.TIMING_DOWN;
                default:          return EvaluationConst.TIMING_NEUTRAL;  // "관망" 등
            }
        }
        // entryTiming 분석 실패 시 step6(모멘텀) 점수로 폴백
        if (step6Score >= EvaluationConst.TIMING_FALLBACK_GOOD_STEP6) return EvaluationConst.TIMING_GOOD;
        if (step6Score >= EvaluationConst.TIMING_FALLBACK_WAIT_STEP6) return EvaluationConst.TIMING_WAIT;
        return EvaluationConst.TIMING_DOWN;
    }

    /**
     * [2-1] 투자판정 (가치등급 × 타이밍 매트릭스)
     * - 가치등급 S/A/B(가치 양호) + 타이밍 양호 → 매수 후보
     * - 가치 양호 + 타이밍 미흡(대기/하락/관망) → 관심목록
     * - 가치 미흡(C/D/F) → 관망 (타이밍 무관, 펀더멘털 부족)
     */
    static String resolveInvestmentSignal(String valueGrade, String timingSignal) {
        boolean valueOk = "S".equals(valueGrade) || "A".equals(valueGrade) || "B".equals(valueGrade);
        if (!valueOk) {
            return EvaluationConst.INVEST_SIGNAL_HOLD;
        }
        if (EvaluationConst.TIMING_GOOD.equals(timingSignal)) {
            return EvaluationConst.INVEST_SIGNAL_BUY;
        }
        return EvaluationConst.INVEST_SIGNAL_WATCH;
    }

    /**
     * [Fix1] 매수후보 품질 게이트
     * - 채권/특수증권 or 이상치 괴리(±2000%) → 관망 (계산 신뢰도 없음, 투자 불가)
     * - 매수후보 + 초소형주(<$300M) or 극단괴리(±200%) → 관심목록 (행동 리스트에서 강등)
     */
    static String applyQualityGate(String signal, Long marketCap, String priceGapPercent,
                                    String symbol, String companyName) {
        double gap = parseGapPercent(priceGapPercent);  // NaN 가능

        // 1) 채권/특수증권 or 이상치 괴리 → 관망
        boolean isBond = SecurityTypeUtil.isNonCommonStock(symbol, companyName);
        boolean isOutlier = !Double.isNaN(gap) && Math.abs(gap) >= EvaluationConst.QUALITY_GATE_OUTLIER_GAP_PCT;
        if (isBond || isOutlier) {
            return EvaluationConst.INVEST_SIGNAL_HOLD;
        }

        // 2) 매수후보 + 초소형주 or 극단괴리 → 관심목록
        if (EvaluationConst.INVEST_SIGNAL_BUY.equals(signal)) {
            boolean isMicroCap = marketCap != null && marketCap < EvaluationConst.QUALITY_GATE_MICROCAP;
            boolean isExtremeGap = !Double.isNaN(gap) && Math.abs(gap) >= EvaluationConst.QUALITY_GATE_UNREALISTIC_GAP_PCT;
            if (isMicroCap || isExtremeGap) {
                return EvaluationConst.INVEST_SIGNAL_WATCH;
            }
        }
        return signal;
    }

    /** 가격차이율 문자열("162.61%")을 double로 파싱 (실패 시 NaN) */
    private static double parseGapPercent(String priceGapPercent) {
        if (StringUtil.isStringEmpty(priceGapPercent) || "N/A".equals(priceGapPercent)) return Double.NaN;
        try {
            return Double.parseDouble(priceGapPercent.replace("%", "").trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /**
     * [2-1] 투자판정 색상 (색각이상 친화: 파랑/노랑/회색)
     */
    static String resolveInvestmentSignalColor(String investmentSignal) {
        if (EvaluationConst.INVEST_SIGNAL_BUY.equals(investmentSignal)) return EvaluationConst.INVEST_COLOR_BUY;
        if (EvaluationConst.INVEST_SIGNAL_WATCH.equals(investmentSignal)) return EvaluationConst.INVEST_COLOR_WATCH;
        return EvaluationConst.INVEST_COLOR_HOLD;
    }

    /**
     * 등급 계산
     * @param totalScore 총점
     * @return 등급 (S, A, B, C, D, F)
     */
    private String calculateGrade(double totalScore) {
        if (totalScore >= 92) return "S";
        if (totalScore >= 83) return "A";
        if (totalScore >= 73) return "B";
        if (totalScore >= 63) return "C";
        if (totalScore >= 50) return "D";
        return "F";
    }

    /**
     * 투자 추천도 생성
     * @param totalScore 총점
     * @param detail 상세정보
     * @return 추천도 메시지
     */
    private String generateRecommendation(double totalScore, CompanySharePriceResultDetail detail) {
        if (detail.is수익가치계산불가()) {
            return "⛔ 투자 비추천: 수익가치 계산 불가, 리스크 매우 높음";
        }

        if (totalScore >= 88) {
            return "🌟 강력 매수 추천: 저평가 + 재무 건전성 우수 + 성장성 우수";
        } else if (totalScore >= 78) {
            return "✅ 매수 추천: 안정적 재무구조 + 합리적 밸류에이션";
        } else if (totalScore >= 68) {
            return "👍 매수 고려 가능: 전반적으로 양호하나 일부 주의 필요";
        } else if (totalScore >= 55) {
            return "⚠️ 신중한 검토 필요: 리스크 요인 존재, 추가 분석 권장";
        } else if (totalScore >= 45) {
            return "⚠️ 투자 주의: 여러 리스크 요인 존재";
        } else {
            return "🚫 투자 비추천: 높은 리스크, 투자 적합하지 않음";
        }
    }

    /**
     * 기업 프로필 조회 (Redis 캐시 적용, TTL 24시간)
     * @param symbol 심볼
     * @return 기업 프로필
     */
    private CompanyProfileDataResDto getCompanyProfile(String symbol) {
        try {
            // Redis 캐시 조회
            String redisKey = RedisKeyGenerator.genAbroadCompanyProfile(symbol);
            String cachedProfile = redisComponent.getValue(redisKey);
            if (!StringUtil.isStringEmpty(cachedProfile)) {
                return new Gson().fromJson(cachedProfile, CompanyProfileDataResDto.class);
            }

            // 캐시 miss → FMP API 호출
            List<CompanyProfileDataResDto> profiles = profileSearchService.findProfileListBySymbol(symbol);
            if (profiles != null && !profiles.isEmpty()) {
                CompanyProfileDataResDto profile = profiles.get(0);
                redisComponent.saveValueWithTtl(redisKey, new Gson().toJson(profile), 86400); // 24시간
                return profile;
            }
        } catch (Exception e) {
            log.warn("Failed to get company profile for {}", symbol, e);
        }
        return null;
    }

    /**
     * 가격 차이 계산 (절대값)
     * @param currentPrice 현재가
     * @param fairValue 적정가
     * @return 가격 차이
     */
    private String calculatePriceDifference(String currentPrice, String fairValue) {
        if (StringUtil.isStringEmpty(currentPrice) || StringUtil.isStringEmpty(fairValue)) {
            return "N/A";
        }

        try {
            BigDecimal current = new BigDecimal(currentPrice);
            BigDecimal fair = new BigDecimal(fairValue);
            BigDecimal diff = fair.subtract(current);
            return diff.setScale(2, RoundingMode.HALF_UP).toPlainString();
        } catch (Exception e) {
            log.warn("Failed to calculate price difference", e);
            return "N/A";
        }
    }

    /**
     * 가격 차이 비율 계산 (%)
     * @param currentPrice 현재가
     * @param fairValue 적정가
     * @return 가격 차이 비율
     */
    private String calculatePriceGapPercent(String currentPrice, String fairValue) {
        if (StringUtil.isStringEmpty(currentPrice) || StringUtil.isStringEmpty(fairValue)) {
            return "N/A";
        }

        try {
            BigDecimal current = new BigDecimal(currentPrice);
            BigDecimal fair = new BigDecimal(fairValue);

            if (current.compareTo(BigDecimal.ZERO) <= 0) {
                return "N/A";
            }

            BigDecimal diff = fair.subtract(current);
            BigDecimal percent = diff.divide(current, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
            return percent.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
        } catch (Exception e) {
            log.warn("Failed to calculate price gap percent", e);
            return "N/A";
        }
    }

    /**
     * [개선3] 52주 고점 대비 하락률 계산
     * @param priceHistory 52주 가격 히스토리 (최신→과거)
     * @param stockQuote 현재 시세
     * @return 하락률 문자열 (예: "-25.30%"), 계산 불가 시 null
     */
    private String calculate52WeekHighDrop(List<StockPriceVolumeResDto> priceHistory,
                                            StockQuoteResDto stockQuote) {
        if (priceHistory == null || priceHistory.isEmpty() || stockQuote == null
                || stockQuote.getPrice() == null) {
            return null;
        }
        try {
            double high52w = priceHistory.stream()
                    .filter(p -> p.getHigh() != null)
                    .mapToDouble(StockPriceVolumeResDto::getHigh)
                    .max()
                    .orElse(0);
            if (high52w <= 0) return null;

            double currentPrice = stockQuote.getPrice();
            double dropPct = (currentPrice - high52w) / high52w * 100;
            return String.format("%.2f%%", dropPct);
        } catch (Exception e) {
            log.warn("52주 고점 대비 하락률 계산 실패: {}", e.getMessage());
            return null;
        }
    }

    /**
     * [개선4] 애널리스트 추정치 조회 (Forward EPS 등)
     * @param symbol 심볼
     * @return 가장 최근 추정치 (없으면 null)
     */
    private AnalystEstimatesResDto fetchAnalystEstimates(String symbol) {
        try {
            AnalystEstimatesReqDto reqDto = new AnalystEstimatesReqDto();
            reqDto.setSymbol(symbol);
            reqDto.setPeriod("annual");
            reqDto.setLimit(1);
            List<AnalystEstimatesResDto> estimates = analystEstimatesService.findAnalystEstimates(reqDto);
            if (estimates != null && !estimates.isEmpty()) {
                return estimates.get(0);
            }
        } catch (Exception e) {
            log.warn("[ForwardPER] {} - 애널리스트 추정치 조회 실패: {}", symbol, e.getMessage());
        }
        return null;
    }

    /**
     * 시가총액 포맷팅
     * @param marketCap 시가총액
     * @return 포맷된 문자열
     */
    private String formatMarketCap(Long marketCap) {
        if (marketCap == null) return "N/A";

        if (marketCap >= 1_000_000_000_000L) {
            return String.format("$%.2fT", marketCap / 1_000_000_000_000.0);
        } else if (marketCap >= 1_000_000_000L) {
            return String.format("$%.2fB", marketCap / 1_000_000_000.0);
        } else if (marketCap >= 1_000_000L) {
            return String.format("$%.2fM", marketCap / 1_000_000.0);
        } else {
            return String.format("$%d", marketCap);
        }
    }

}
