package com.finance.dart.api.common.service;

import com.finance.dart.api.common.constants.PriceRangeConst;
import com.finance.dart.api.abroad.dto.fmp.chart.StockPriceVolumeResDto;
import com.finance.dart.api.abroad.dto.fmp.quote.StockQuoteResDto;
import com.finance.dart.api.common.dto.CompanySharePriceResultDetail;
import com.finance.dart.api.common.dto.evaluation.EntryTimingAnalysis;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * V8: 모멘텀/기술적 분석 서비스
 * - 이동평균선(SMA50/200) 분석
 * - RSI 14일
 * - 거래량 추세
 * - 하드 게이트 (데스크로스 + 약세 등)
 */
@Slf4j
@AllArgsConstructor
@Service
public class TechnicalAnalysisService {

    /**
     * 기술적 분석 결과
     */
    @Getter
    public static class TechnicalAnalysisResult {
        private final int maScore;          // 0~6
        private final int rsiScore;         // 0~5
        private final int volumeScore;      // 0~4
        private final boolean gatePass;     // 하드 게이트 통과 여부
        private final String gateReason;    // 게이트 실패 사유

        public TechnicalAnalysisResult(int maScore, int rsiScore, int volumeScore,
                                       boolean gatePass, String gateReason) {
            this.maScore = maScore;
            this.rsiScore = rsiScore;
            this.volumeScore = volumeScore;
            this.gatePass = gatePass;
            this.gateReason = gateReason;
        }

        public int getTotalScore() {
            return maScore + rsiScore + volumeScore;
        }
    }

    /**
     * 기술적 분석 수행
     * @param stockQuote StockQuote 데이터 (SMA50, SMA200, price)
     * @param priceHistory 52주 일별 가격 히스토리 (RSI, 거래량용)
     * @param resultDetail 분석 결과를 기록할 DTO
     * @return 분석 결과
     */
    public TechnicalAnalysisResult analyze(StockQuoteResDto stockQuote,
                                            List<StockPriceVolumeResDto> priceHistory,
                                            CompanySharePriceResultDetail resultDetail) {

        int maScore = 0;
        int rsiScore = 0;
        int volumeScore = 3;  // 데이터 없으면 중립
        boolean gatePass = true;
        String gateReason = "";

        // 1. 이동평균선 분석
        if (stockQuote != null && stockQuote.getPrice() != null
                && stockQuote.getPriceAvg50() != null && stockQuote.getPriceAvg200() != null) {

            double price = stockQuote.getPrice();
            double sma50 = stockQuote.getPriceAvg50();
            double sma200 = stockQuote.getPriceAvg200();

            if (price > sma50 && sma50 > sma200) {
                // 강세 배열: price > SMA50 > SMA200
                maScore = 6;
            } else if (price > sma50 || price > sma200) {
                // 일부 강세
                maScore = 3;
            } else {
                // 약세 배열
                maScore = 0;
            }

            // 하드 게이트: 데스크로스(SMA50 < SMA200) AND 주가 < SMA200
            if (sma50 < sma200 && price < sma200) {
                gatePass = false;
                gateReason = "데스크로스(SMA50<SMA200) + 주가<SMA200";
            }

            resultDetail.setSMA50(String.format("%.2f", sma50));
            resultDetail.setSMA200(String.format("%.2f", sma200));
        }

        // 2. RSI 14일 계산 + 3. 거래량 추세
        if (priceHistory != null && priceHistory.size() >= 20) {
            // RSI 계산 (최근 14일 close 기반)
            double rsi = calculateRSI(priceHistory, 14);
            resultDetail.setRSI(String.format("%.1f", rsi));

            if (rsi >= 40 && rsi <= 60) {
                rsiScore = 5;  // 건강한 중립
            } else if (rsi >= 30 && rsi < 40) {
                rsiScore = 4;  // 과매도 접근
            } else if (rsi > 60 && rsi <= 70) {
                rsiScore = 3;  // 약간 과매수
            } else if (rsi < 30) {
                rsiScore = 1;  // 극단 과매도 (위험)
            } else {
                rsiScore = 0;  // >70 과매수
            }

            // 하드 게이트: RSI < 20 AND 주가 < SMA50
            if (rsi < 20 && stockQuote != null && stockQuote.getPrice() != null
                    && stockQuote.getPriceAvg50() != null
                    && stockQuote.getPrice() < stockQuote.getPriceAvg50()) {
                gatePass = false;
                gateReason = gateReason.isEmpty() ? "RSI<20 + 주가<SMA50" : gateReason + " / RSI<20 + 주가<SMA50";
            }

            // 거래량 추세 (최근 10일 평균 / 이전 10일 평균)
            double volumeRatio = calculateVolumeRatio(priceHistory);
            if (volumeRatio > 0) {
                resultDetail.set거래량비율(String.format("%.2f", volumeRatio));

                if (volumeRatio < 0.8) {
                    volumeScore = 4;  // 매도 압력 감소
                } else if (volumeRatio <= 1.0) {
                    volumeScore = 3;
                } else if (volumeRatio <= 1.2) {
                    volumeScore = 2;  // 중립
                } else {
                    volumeScore = 0;  // 매도 압력 증가
                }
            }
        }

        // 결과를 resultDetail에 기록
        resultDetail.set모멘텀_MA점수(maScore);
        resultDetail.set모멘텀_RSI점수(rsiScore);
        resultDetail.set모멘텀_거래량점수(volumeScore);
        resultDetail.set모멘텀게이트통과(gatePass);
        resultDetail.set모멘텀게이트사유(gatePass ? "통과" : gateReason);

        return new TechnicalAnalysisResult(maScore, rsiScore, volumeScore, gatePass, gateReason);
    }

    /**
     * RSI 14일 계산
     * @param priceHistory 일별 가격 (최신 → 과거 순서)
     * @param period RSI 기간 (일반적으로 14)
     * @return RSI 값 (0~100)
     */
    private double calculateRSI(List<StockPriceVolumeResDto> priceHistory, int period) {
        if (priceHistory.size() < period + 1) return 50.0;  // 데이터 부족 시 중립

        double avgGain = 0;
        double avgLoss = 0;

        // 첫 번째 평균 계산 (최신 데이터가 index 0)
        for (int i = 0; i < period; i++) {
            Double current = priceHistory.get(i).getClose();
            Double previous = priceHistory.get(i + 1).getClose();
            if (current == null || previous == null) continue;

            double change = current - previous;
            if (change > 0) {
                avgGain += change;
            } else {
                avgLoss += Math.abs(change);
            }
        }

        avgGain /= period;
        avgLoss /= period;

        if (avgLoss == 0) return 100.0;

        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    /**
     * 거래량 비율 계산 (최근 10일 평균 / 이전 10일 평균)
     * @param priceHistory 일별 가격 (최신 → 과거 순서)
     * @return 거래량 비율 (> 1.0이면 거래량 증가)
     */
    private double calculateVolumeRatio(List<StockPriceVolumeResDto> priceHistory) {
        if (priceHistory.size() < 20) return -1;

        double recentSum = 0;
        double olderSum = 0;
        int recentCount = 0;
        int olderCount = 0;

        for (int i = 0; i < 10; i++) {
            Long vol = priceHistory.get(i).getVolume();
            if (vol != null && vol > 0) {
                recentSum += vol;
                recentCount++;
            }
        }

        for (int i = 10; i < 20; i++) {
            Long vol = priceHistory.get(i).getVolume();
            if (vol != null && vol > 0) {
                olderSum += vol;
                olderCount++;
            }
        }

        if (recentCount == 0 || olderCount == 0) return -1;

        double recentAvg = recentSum / recentCount;
        double olderAvg = olderSum / olderCount;

        if (olderAvg == 0) return -1;

        return recentAvg / olderAvg;
    }

    // ============================================================
    //  진입 타이밍 분석 (Entry Timing Analysis)
    // ============================================================

    /**
     * 진입 타이밍 분석
     * SMA5/20, MACD(12,26,9), 볼린저밴드(20,2), 스토캐스틱(14,3), ATR(14) 기반
     *
     * @param stockQuote 현재 시세 정보
     * @param priceHistory 52주 일별 가격 (최신 → 과거 순서)
     * @return 진입 타이밍 분석 결과 (데이터 부족 시 null)
     */
    public EntryTimingAnalysis analyzeEntryTiming(StockQuoteResDto stockQuote,
                                                   List<StockPriceVolumeResDto> priceHistory) {
        if (priceHistory == null || priceHistory.size() < 30) {
            log.debug("진입 타이밍 분석 불가: 데이터 부족 ({}일)", priceHistory == null ? 0 : priceHistory.size());
            return null;
        }

        try {
            // 종가 리스트 추출 (최신→과거)
            List<Double> closes = extractCloses(priceHistory);
            if (closes.size() < 26) return null;

            double currentPrice = closes.get(0);

            // 1. SMA5, SMA20
            double sma5 = calculateSMA(closes, 5);
            double sma20 = calculateSMA(closes, 20);

            // 2. MACD (12, 26, 9)
            double[] macd = calculateMACD(closes, 12, 26, 9);
            double macdLine = macd[0];
            double macdSignal = macd[1];
            double macdHistogram = macd[2];

            // 어제 MACD 히스토그램 (증가 추세 판단용)
            List<Double> closesYesterday = closes.subList(1, closes.size());
            double[] macdYesterday = calculateMACD(closesYesterday, 12, 26, 9);
            double prevHistogram = macdYesterday[2];

            // 3. 볼린저밴드 (20, 2)
            double[] bollinger = calculateBollingerBands(closes, 20, 2.0);
            double bollingerUpper = bollinger[0];
            double bollingerMiddle = bollinger[1];
            double bollingerLower = bollinger[2];

            // 4. 스토캐스틱 (14, 3)
            double[] stochastic = calculateStochastic(priceHistory, 14, 3);
            double stochasticK = stochastic[0];
            double stochasticD = stochastic[1];

            // 5. ATR (14)
            double atr = calculateATR(priceHistory, 14);

            // 6. RSI (기존 메서드 활용)
            double rsi = calculateRSI(priceHistory, 14);
            // 어제 RSI (상승 추세 판단용)
            double prevRsi = priceHistory.size() > 15 ?
                    calculateRSI(priceHistory.subList(1, priceHistory.size()), 14) : rsi;

            // ── 종합 점수 계산 ──
            int score = 50; // 기준점

            // SMA5 > SMA20: +20 (단기 상승), SMA5 < SMA20: -10
            if (sma5 > sma20) {
                score += 20;
            } else {
                score -= 10;
            }

            // MACD > Signal: +20 (모멘텀 양전환), MACD < Signal: -10
            if (macdLine > macdSignal) {
                score += 20;
            } else {
                score -= 10;
            }

            // MACD Histogram 증가중: +10
            if (macdHistogram > prevHistogram) {
                score += 10;
            }

            // Stochastic K < 20: +15 (과매도 = 반등 가능)
            // Stochastic K > 80: -15 (과매수 = 조정 가능)
            if (stochasticK < 20) {
                score += 15;
            } else if (stochasticK > 80) {
                score -= 15;
            }

            // 볼린저 하단 근접(<10%): +15, 상단 근접(<10%): -10
            double bollingerRange = bollingerUpper - bollingerLower;
            if (bollingerRange > 0) {
                double lowerProximity = (currentPrice - bollingerLower) / bollingerRange;
                double upperProximity = (bollingerUpper - currentPrice) / bollingerRange;
                if (lowerProximity < 0.10) {
                    score += 15;
                } else if (upperProximity < 0.10) {
                    score -= 10;
                }
            }

            // RSI 30~50 + 상승중: +10 (회복 구간)
            if (rsi >= 30 && rsi <= 50 && rsi > prevRsi) {
                score += 10;
            }

            // ── 반전 리스크 감점 ──

            // ① RSI > 70: 과매수 감점
            if (rsi > 70) {
                score -= 10;
            }

            // ② 연속 상승일수 체크 (5일 이상이면 과열)
            int consecutiveUp = 0;
            for (int i = 0; i < closes.size() - 1 && i < 10; i++) {
                if (closes.get(i) > closes.get(i + 1)) {
                    consecutiveUp++;
                } else {
                    break;
                }
            }
            if (consecutiveUp >= 5) {
                score -= 10;
            }

            // ③ SMA5-SMA20 괴리도 > 3%: 평균 회귀 압력
            if (sma20 > 0) {
                double divergence = Math.abs(sma5 - sma20) / sma20;
                if (divergence > 0.03 && sma5 > sma20) {
                    score -= 10;
                }
            }

            // ④ 현재가가 20일 고점의 97% 이상: 상승 여력 제한
            double high20 = closes.stream().limit(20).mapToDouble(Double::doubleValue).max().orElse(0);
            if (high20 > 0 && currentPrice >= high20 * 0.97) {
                score -= 5;
            }

            // 범위 제한
            score = Math.max(0, Math.min(100, score));

            // ── 시그널 결정 ──
            String signal;
            String signalColor;
            if (score >= 70) {
                signal = "매수 적기";
                signalColor = "green";
            } else if (score >= 50) {
                signal = "관망";
                signalColor = "gray";
            } else if (score >= 30) {
                signal = "대기 권장";
                signalColor = "yellow";
            } else {
                signal = "하락 구간";
                signalColor = "red";
            }

            // ── 단기 추세 판단 ──
            String shortTermTrend;
            StringBuilder trendDetailSb = new StringBuilder();
            if (sma5 > sma20) {
                shortTermTrend = "상승";
                trendDetailSb.append("SMA5가 SMA20 위");
            } else if (Math.abs(sma5 - sma20) / sma20 < 0.005) {
                shortTermTrend = "횡보";
                trendDetailSb.append("SMA5와 SMA20 근접");
            } else {
                shortTermTrend = "하락";
                trendDetailSb.append("SMA5가 SMA20 아래");
            }

            if (macdLine > macdSignal) {
                trendDetailSb.append(", MACD 양전환");
            } else {
                trendDetailSb.append(", MACD 음전환");
            }

            if (macdHistogram > prevHistogram) {
                trendDetailSb.append("(히스토그램 증가중)");
            }

            // ── 상세 설명 ──
            StringBuilder desc = new StringBuilder();
            desc.append(String.format("단기추세 %s. ", shortTermTrend));
            if (stochasticK < 20) {
                desc.append("스토캐스틱 과매도 구간(반등 가능). ");
            } else if (stochasticK > 80) {
                desc.append("스토캐스틱 과매수 구간(조정 가능). ");
            }
            if (bollingerRange > 0) {
                double lowerProximity = (currentPrice - bollingerLower) / bollingerRange;
                if (lowerProximity < 0.10) {
                    desc.append("볼린저밴드 하단 근접(지지 기대). ");
                }
            }
            if (rsi > 70) {
                desc.append("RSI 과매수 구간(조정 가능). ");
            }
            if (consecutiveUp >= 5) {
                desc.append(String.format("연속 %d일 상승(단기 과열). ", consecutiveUp));
            }
            desc.append(String.format("ATR $%.2f (일일 예상 변동폭).", atr));

            return EntryTimingAnalysis.builder()
                    .signal(signal)
                    .signalColor(signalColor)
                    .timingScore(score)
                    .description(desc.toString())
                    .shortTermTrend(shortTermTrend)
                    .trendDetail(trendDetailSb.toString())
                    .rangeDays(PriceRangeConst.RANGE_DAYS)
                    .rangeAtrMultiple(PriceRangeConst.RANGE_ATR_MULTIPLE)
                    .rangeLow(round2(Math.max(0, currentPrice - PriceRangeConst.RANGE_ATR_MULTIPLE * atr)))
                    .rangeHigh(round2(currentPrice + PriceRangeConst.RANGE_ATR_MULTIPLE * atr))
                    .rangeBreakDownProb(PriceRangeConst.rangeProb().down())
                    .rangeBreakUpProb(PriceRangeConst.rangeProb().up())
                    .rangeProbTable(PriceRangeConst.BREAK_PROB_TABLE)
                    .estimatedSupport(round2(bollingerLower))
                    .estimatedResistance(round2(bollingerUpper))
                    .sma5(round2(sma5))
                    .sma20(round2(sma20))
                    .macdLine(round2(macdLine))
                    .macdSignal(round2(macdSignal))
                    .macdHistogram(round2(macdHistogram))
                    .bollingerUpper(round2(bollingerUpper))
                    .bollingerMiddle(round2(bollingerMiddle))
                    .bollingerLower(round2(bollingerLower))
                    .stochasticK(round2(stochasticK))
                    .stochasticD(round2(stochasticD))
                    .atr(round2(atr))
                    .build();

        } catch (Exception e) {
            log.warn("진입 타이밍 분석 중 오류: {}", e.getMessage());
            return null;
        }
    }

    // ── 헬퍼 메서드 ──

    /**
     * 종가 리스트 추출 (null 제거)
     */
    private List<Double> extractCloses(List<StockPriceVolumeResDto> priceHistory) {
        List<Double> closes = new ArrayList<>();
        for (StockPriceVolumeResDto dto : priceHistory) {
            if (dto.getClose() != null) {
                closes.add(dto.getClose());
            }
        }
        return closes;
    }

    /**
     * SMA 계산
     */
    private double calculateSMA(List<Double> values, int period) {
        if (values.size() < period) return values.get(0);
        double sum = 0;
        for (int i = 0; i < period; i++) {
            sum += values.get(i);
        }
        return sum / period;
    }

    /**
     * EMA 계산
     * @param values 값 리스트 (최신→과거)
     * @param period EMA 기간
     * @return EMA 값
     */
    private double calculateEMA(List<Double> values, int period) {
        if (values.size() < period) return calculateSMA(values, values.size());

        double multiplier = 2.0 / (period + 1);

        // 역순으로 진행 (과거→최신)
        // 초기 SMA로 시작
        double ema = 0;
        int startIdx = values.size() - 1;
        int endIdx = Math.max(0, values.size() - period);

        // 가장 오래된 period 개의 평균으로 시작
        double sum = 0;
        for (int i = startIdx; i > startIdx - period && i >= 0; i--) {
            sum += values.get(i);
        }
        ema = sum / period;

        // 과거에서 최신으로 진행
        for (int i = startIdx - period; i >= 0; i--) {
            ema = (values.get(i) - ema) * multiplier + ema;
        }

        return ema;
    }

    /**
     * MACD 계산 (12, 26, 9)
     * @return [macdLine, signalLine, histogram]
     */
    private double[] calculateMACD(List<Double> closes, int fast, int slow, int signal) {
        double emaFast = calculateEMA(closes, fast);
        double emaSlow = calculateEMA(closes, slow);
        double macdLine = emaFast - emaSlow;

        // MACD 히스토리 계산 (시그널 라인용)
        List<Double> macdHistory = new ArrayList<>();
        for (int i = 0; i < closes.size() && i < slow + signal; i++) {
            List<Double> subCloses = closes.subList(i, closes.size());
            if (subCloses.size() >= slow) {
                double ef = calculateEMA(subCloses, fast);
                double es = calculateEMA(subCloses, slow);
                macdHistory.add(ef - es);
            }
        }

        double signalLine = macdHistory.size() >= signal ?
                calculateEMA(macdHistory, signal) : macdLine;

        return new double[]{macdLine, signalLine, macdLine - signalLine};
    }

    /**
     * 볼린저밴드 계산 (period, multiplier)
     * @return [upper, middle, lower]
     */
    private double[] calculateBollingerBands(List<Double> closes, int period, double multiplier) {
        double sma = calculateSMA(closes, period);
        int len = Math.min(closes.size(), period);

        double sumSq = 0;
        for (int i = 0; i < len; i++) {
            double diff = closes.get(i) - sma;
            sumSq += diff * diff;
        }
        double stdDev = Math.sqrt(sumSq / len);

        return new double[]{
                sma + multiplier * stdDev,
                sma,
                sma - multiplier * stdDev
        };
    }

    /**
     * 스토캐스틱 계산 (%K, %D)
     * @param priceHistory 일별 가격 (최신→과거, high/low/close 필요)
     * @param kPeriod %K 기간 (14)
     * @param dPeriod %D 기간 (3)
     * @return [%K, %D]
     */
    private double[] calculateStochastic(List<StockPriceVolumeResDto> priceHistory, int kPeriod, int dPeriod) {
        if (priceHistory.size() < kPeriod + dPeriod) return new double[]{50, 50};

        List<Double> kValues = new ArrayList<>();
        for (int i = 0; i < dPeriod + 1 && i + kPeriod <= priceHistory.size(); i++) {
            double highMax = Double.MIN_VALUE;
            double lowMin = Double.MAX_VALUE;
            for (int j = i; j < i + kPeriod; j++) {
                StockPriceVolumeResDto bar = priceHistory.get(j);
                if (bar.getHigh() != null) highMax = Math.max(highMax, bar.getHigh());
                if (bar.getLow() != null) lowMin = Math.min(lowMin, bar.getLow());
            }
            double close = priceHistory.get(i).getClose() != null ? priceHistory.get(i).getClose() : 0;
            double range = highMax - lowMin;
            double k = range > 0 ? ((close - lowMin) / range) * 100 : 50;
            kValues.add(k);
        }

        double currentK = kValues.isEmpty() ? 50 : kValues.get(0);

        // %D = %K의 dPeriod 이동평균
        double dSum = 0;
        int dCount = Math.min(dPeriod, kValues.size());
        for (int i = 0; i < dCount; i++) {
            dSum += kValues.get(i);
        }
        double currentD = dCount > 0 ? dSum / dCount : 50;

        return new double[]{currentK, currentD};
    }

    /**
     * ATR 계산 (Average True Range)
     * @param priceHistory 일별 가격 (최신→과거)
     * @param period ATR 기간 (14)
     * @return ATR 값
     */
    private double calculateATR(List<StockPriceVolumeResDto> priceHistory, int period) {
        if (priceHistory.size() < period + 1) return 0;

        double sum = 0;
        for (int i = 0; i < period; i++) {
            StockPriceVolumeResDto current = priceHistory.get(i);
            StockPriceVolumeResDto previous = priceHistory.get(i + 1);

            double high = current.getHigh() != null ? current.getHigh() : 0;
            double low = current.getLow() != null ? current.getLow() : 0;
            double prevClose = previous.getClose() != null ? previous.getClose() : 0;

            double tr = Math.max(
                    high - low,
                    Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose))
            );
            sum += tr;
        }

        return sum / period;
    }

    /**
     * 소수점 2자리 반올림
     */
    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
