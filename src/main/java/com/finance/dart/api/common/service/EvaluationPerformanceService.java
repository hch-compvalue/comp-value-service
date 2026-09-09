package com.finance.dart.api.common.service;

import com.finance.dart.api.common.dto.evaluation.PerformanceSummaryDto;
import com.finance.dart.api.common.entity.EvaluationHistoryEntity;
import com.finance.dart.api.common.repository.EvaluationHistoryRepository;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;

/**
 * 수익률 추적 서비스 (2-4)
 * - 일별 평가가 매일 저장한 current_price만으로 수익률 계산 (추가 FMP 호출 없음)
 * - "특정 기준일의 투자판정/가치등급별로 이후 실제 수익률이 어땠는가" 집계 → 신뢰도 데이터 검증
 */
@Slf4j
@Service
@AllArgsConstructor
public class EvaluationPerformanceService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /**
     * 1일 기준 큰 변동 판정 임계치 (%).
     * 이 값을 넘는 종목만 분할·병합 여부를 따로 판별한다 (아래 classifyLargeMove).
     * 변동이 작은 구간에서는 주식수·적정가 배수가 모두 1에 가까워 판별력이 없으므로 1차 관문으로만 쓴다.
     */
    private static final double LARGE_MOVE_THRESHOLD_1D_PCT = 50.0;

    /** 경과일수 최소값 - 임계치가 0이 되는 것을 방지 */
    private static final long MIN_ELAPSED_DAYS = 1L;

    /**
     * 발행주식수 변동 허용치 (비율).
     * 자사주 매입·소각은 보통 이 범위 안에서 움직이고, 분할·병합은 배수 단위로 변하므로 둘을 가른다.
     */
    private static final double SHARES_CHANGE_TOLERANCE = 0.20;

    /**
     * 적정가 "불변" 판정 허용치 (비율).
     * 적정가는 재무제표 기반 주당 가치라 실적 갱신이 없으면 그대로다.
     * 급등락은 적정가를 바꾸지 않으므로, 불변이면 실제 주가 변동으로 본다.
     */
    private static final double FAIR_VALUE_STABLE_TOLERANCE = 0.01;

    private final EvaluationHistoryRepository evaluationHistoryRepository;

    /** 큰 변동의 성격 판정 결과 */
    enum PriceMoveVerdict {
        /** 실제 주가 변동 - 집계에 포함 */
        REAL_MOVE,
        /** 분할·병합 의심 또는 판별 불가 - 집계에서 제외 */
        EXCLUDED
    }

    /**
     * 기준일 대비 최신일까지의 수익률을 투자판정·가치등급별로 집계
     * @param fromDate 기준일 (해당일에 평가된 종목들의 이후 성과)
     * @return {"bySignal": [...], "byGrade": [...], "baseDate":..., "latestDate":..., "largeMoveThresholdPct":...}
     */
    @Transactional(readOnly = true)
    public Map<String, Object> computePerformance(LocalDate fromDate) {
        Map<String, Object> result = new LinkedHashMap<>();

        List<EvaluationHistoryEntity> baseline = evaluationHistoryRepository.findBySnapshotDate(fromDate);
        LocalDate latestDate = evaluationHistoryRepository.findFirstByOrderBySnapshotDateDesc()
                .map(EvaluationHistoryEntity::getSnapshotDate)
                .orElse(null);

        result.put("baseDate", fromDate != null ? fromDate.toString() : null);
        result.put("latestDate", latestDate != null ? latestDate.toString() : null);

        if (baseline.isEmpty() || latestDate == null || latestDate.equals(fromDate)) {
            // 데이터 부족(기준일=최신일이면 경과기간 0) → 빈 집계
            result.put("bySignal", List.of());
            result.put("byGrade", List.of());
            result.put("note", "수익률 산출에는 기준일 이후 경과한 스냅샷이 필요합니다.");
            return result;
        }

        Map<String, EvaluationHistoryEntity> latestBySymbol = new HashMap<>();
        for (EvaluationHistoryEntity e : evaluationHistoryRepository.findBySnapshotDate(latestDate)) {
            latestBySymbol.put(e.getSymbol(), e);
        }

        long elapsedDays = Math.max(MIN_ELAPSED_DAYS, ChronoUnit.DAYS.between(fromDate, latestDate));
        result.put("largeMoveThresholdPct", round2(largeMoveThresholdPct(elapsedDays)));
        result.put("bySignal", computeSummaries(baseline, latestBySymbol, EvaluationHistoryEntity::getInvestmentSignal, elapsedDays));
        result.put("byGrade", computeSummaries(baseline, latestBySymbol, EvaluationHistoryEntity::getValueGrade, elapsedDays));
        return result;
    }

    /**
     * 큰 변동 판정 임계치 (%) - 경과일수의 제곱근에 비례해 완화
     * 주가 변동성은 기간의 제곱근에 비례하므로, 장기 구간에서 정상적인 큰 상승까지 후보로 잡지 않도록 √t로 스케일링한다.
     * 예) 1일 50%, 4일 100%, 25일 250%
     */
    static double largeMoveThresholdPct(long elapsedDays) {
        return LARGE_MOVE_THRESHOLD_1D_PCT * Math.sqrt(Math.max(MIN_ELAPSED_DAYS, elapsedDays));
    }

    /**
     * 임계치를 넘은 큰 변동이 실제 주가 변동인지 분할·병합인지 판정
     * 판별 근거를 신뢰도 순으로 적용하고, 어느 것도 못 쓰면 보수적으로 제외한다.
     * @param base 기준일 스냅샷
     * @param latest 최신일 스냅샷
     */
    static PriceMoveVerdict classifyLargeMove(EvaluationHistoryEntity base, EvaluationHistoryEntity latest) {
        // 1순위: 발행주식수 - 분할·병합 때만 변하고 실적 발표에는 반응하지 않아 구간이 길어도 판별력이 유지된다
        BigDecimal baseShares = base.getSharesOutstanding();
        BigDecimal latestShares = latest.getSharesOutstanding();
        if (isPositive(baseShares) && isPositive(latestShares)) {
            double ratio = latestShares.doubleValue() / baseShares.doubleValue();
            return Math.abs(ratio - 1.0) > SHARES_CHANGE_TOLERANCE
                    ? PriceMoveVerdict.EXCLUDED : PriceMoveVerdict.REAL_MOVE;
        }

        // 2순위: 적정가 - 주식수가 없는 과거 스냅샷용 폴백. 계산식에 주가가 들어가지 않아 급등락에 반응하지 않는다
        BigDecimal baseFair = base.getFairValue();
        BigDecimal latestFair = latest.getFairValue();
        if (baseFair != null && latestFair != null
                && baseFair.signum() != 0 && baseFair.signum() == latestFair.signum()) {
            double ratio = latestFair.doubleValue() / baseFair.doubleValue();
            // 적정가가 그대로면 실제 주가 변동. 함께 움직였으면 분할·병합, 따로 움직였으면 판별 불가 → 모두 제외
            return Math.abs(ratio - 1.0) < FAIR_VALUE_STABLE_TOLERANCE
                    ? PriceMoveVerdict.REAL_MOVE : PriceMoveVerdict.EXCLUDED;
        }

        return PriceMoveVerdict.EXCLUDED;
    }

    private static boolean isPositive(BigDecimal v) {
        return v != null && v.signum() > 0;
    }

    /**
     * 그룹별 수익률 집계 (순수 함수 - 단위 테스트 대상)
     * @param baseline 기준일 평가 종목들 (currentPrice = 기준가)
     * @param latestBySymbol 최신일 종목별 스냅샷
     * @param keyFn 그룹 키 추출 (투자판정 or 가치등급)
     * @param elapsedDays 기준일~최신일 경과일수 (큰 변동 임계치 산정용)
     */
    static List<PerformanceSummaryDto> computeSummaries(
            List<EvaluationHistoryEntity> baseline,
            Map<String, EvaluationHistoryEntity> latestBySymbol,
            Function<EvaluationHistoryEntity, String> keyFn,
            long elapsedDays) {

        final double threshold = largeMoveThresholdPct(elapsedDays);

        // 그룹 → 수익률 리스트 / 그룹 → 분할·병합 의심 제외 건수
        Map<String, List<Double>> byGroup = new LinkedHashMap<>();
        Map<String, Integer> excludedByGroup = new LinkedHashMap<>();
        for (EvaluationHistoryEntity e : baseline) {
            EvaluationHistoryEntity latest = latestBySymbol.get(e.getSymbol());
            BigDecimal basePrice = e.getCurrentPrice();
            BigDecimal latestPrice = (latest != null) ? latest.getCurrentPrice() : null;
            if (basePrice == null || latestPrice == null
                    || basePrice.compareTo(BigDecimal.ZERO) <= 0) {
                continue;  // 가격 없거나 기준가 0 이하 → 제외
            }
            String key = keyFn.apply(e);
            if (key == null) key = "(미분류)";

            double returnPct = latestPrice.subtract(basePrice)
                    .divide(basePrice, 6, java.math.RoundingMode.HALF_UP)
                    .multiply(HUNDRED)
                    .doubleValue();

            // 임계치를 넘은 변동만 성격을 따진다 - 분할·병합이면 평균이 왜곡되므로 빼고 건수만 남긴다
            if (Math.abs(returnPct) > threshold
                    && classifyLargeMove(e, latest) == PriceMoveVerdict.EXCLUDED) {
                excludedByGroup.merge(key, 1, Integer::sum);
                byGroup.computeIfAbsent(key, k -> new ArrayList<>());  // 제외만 있는 그룹도 행이 남도록
                continue;
            }
            byGroup.computeIfAbsent(key, k -> new ArrayList<>()).add(returnPct);
        }

        List<PerformanceSummaryDto> summaries = new ArrayList<>();
        for (Map.Entry<String, List<Double>> entry : byGroup.entrySet()) {
            List<Double> returns = entry.getValue();
            int n = returns.size();
            int excluded = excludedByGroup.getOrDefault(entry.getKey(), 0);
            if (n == 0) {
                // 전부 제외된 그룹 - 0으로 나누지 않도록 집계값은 비우고 제외 건수만 알린다
                summaries.add(PerformanceSummaryDto.builder()
                        .group(entry.getKey())
                        .count(0)
                        .excludedCount(excluded)
                        .build());
                continue;
            }
            double sum = 0, max = Double.NEGATIVE_INFINITY, min = Double.POSITIVE_INFINITY;
            int wins = 0;
            for (double r : returns) {
                sum += r;
                if (r > max) max = r;
                if (r < min) min = r;
                if (r > 0) wins++;
            }
            summaries.add(PerformanceSummaryDto.builder()
                    .group(entry.getKey())
                    .count(n)
                    .avgReturnPct(round2(sum / n))
                    .medianReturnPct(round2(median(returns)))
                    .winRatePct(round2(wins * 100.0 / n))
                    .maxReturnPct(round2(max))
                    .minReturnPct(round2(min))
                    .excludedCount(excluded)
                    .build());
        }
        return summaries;
    }

    /**
     * 중앙값 계산 (짝수 개면 가운데 두 값의 평균)
     * @param values 비어있지 않은 수익률 목록 (호출부에서 크기 검증)
     */
    static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        int mid = n / 2;
        return (n % 2 == 1) ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
