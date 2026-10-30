package com.finance.dart.api.domestic.valuation;

import com.finance.dart.api.domestic.collect.financial.KrFinancialCollector;
import com.finance.dart.api.domestic.collect.financial.KrReportType;
import com.finance.dart.api.domestic.entity.KrFinancialReportEntity;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.function.Function;

/**
 * 기준일 시점에 공시된 재무 보고서로 만든 계산용 스냅샷
 * - 기준일 이후 접수된 보고서는 쓰지 않는다 (6단계 백테스트에서 미래 정보가 섞이지 않게 — 실시간 계산은 기준일 = 오늘)
 * - 분기 단독 값: 1분기·반기·3분기는 보고서의 3개월 값, 4분기는 사업보고서 연간 − 같은 회계연도 3분기 누적(결산기 종료일로 짝)
 * - 순이익은 지배주주 순이익 우선, 없으면(별도재무) 당기순이익
 * - 단위 이상 보고서 제외: 총자산이 회사 보고서들의 중앙값과 300배 넘게 차이 나면 뺀다
 *   (10-30 실데이터: 일부 회사가 특정 보고서를 천 원 단위로 공시 → 금액 1,000배, 3.8만 건 중 7건. 나눠서 고치면 단위 추정이라 제외)
 */
public final class KrFinancialSnapshot {

    static final int QUARTERS_PER_YEAR = 4;
    /** 총자산이 중앙값 대비 이 배수를 넘으면(또는 1/배수 미만) 단위 이상으로 본다 */
    static final double UNIT_ANOMALY_RATIO = 300;
    private static final int MONTHS_PER_QUARTER = 3;

    /** 분기 단독 값 */
    public record Quarter(LocalDate periodEnd, Long operatingIncome, Long revenue, Long netIncome) {}

    private final List<KrFinancialReportEntity> annuals;          // 사업보고서, 최신순
    private final SortedMap<LocalDate, Quarter> quarters;         // 기간 종료일 오름차순
    private final KrFinancialReportEntity latest;                 // 기간이 가장 최근인 보고서 (재무상태표 기준)
    private final int unitAnomalies;                              // 단위 이상으로 뺀 보고서 수

    private KrFinancialSnapshot(List<KrFinancialReportEntity> annuals, SortedMap<LocalDate, Quarter> quarters,
                                KrFinancialReportEntity latest, int unitAnomalies) {
        this.annuals = annuals;
        this.quarters = quarters;
        this.latest = latest;
        this.unitAnomalies = unitAnomalies;
    }

    public static KrFinancialSnapshot build(List<KrFinancialReportEntity> reports, LocalDate asOf) {
        List<KrFinancialReportEntity> disclosed = reports.stream()
                .filter(r -> !KrFinancialCollector.FS_NONE.equals(r.getFsDiv()))
                .filter(r -> r.getRceptDate() != null && !r.getRceptDate().isAfter(asOf))
                .sorted(Comparator.comparing(KrFinancialReportEntity::getPeriodEnd).reversed())
                .toList();
        Double medianAssets = medianTotalAssets(disclosed);
        List<KrFinancialReportEntity> usable = disclosed.stream().filter(r -> !isUnitAnomaly(r, medianAssets)).toList();

        String annualCode = KrReportType.ANNUAL.code();
        String q3Code = KrReportType.Q3.code();
        List<KrFinancialReportEntity> annuals = usable.stream().filter(r -> annualCode.equals(r.getReprtCode())).toList();
        Map<LocalDate, KrFinancialReportEntity> q3ByPeriod = new HashMap<>();
        usable.stream().filter(r -> q3Code.equals(r.getReprtCode())).forEach(r -> q3ByPeriod.put(r.getPeriodEnd(), r));

        SortedMap<LocalDate, Quarter> quarters = new TreeMap<>();
        for (KrFinancialReportEntity r : usable) {
            if (annualCode.equals(r.getReprtCode())) {
                KrFinancialReportEntity q3 = q3ByPeriod.get(KrReportType.q3PeriodEndOf(r.getPeriodEnd()));
                if (q3 == null) continue;
                quarters.put(r.getPeriodEnd(), new Quarter(r.getPeriodEnd(),
                        KrReportType.q4Standalone(r.getOperatingIncomeYtd(), q3.getOperatingIncomeYtd()),
                        KrReportType.q4Standalone(r.getRevenueYtd(), q3.getRevenueYtd()),
                        KrReportType.q4Standalone(netIncome(r.getNetIncomeOwnersYtd(), r.getNetIncomeYtd()),
                                netIncome(q3.getNetIncomeOwnersYtd(), q3.getNetIncomeYtd()))));
            } else {
                quarters.put(r.getPeriodEnd(), new Quarter(r.getPeriodEnd(), r.getOperatingIncomeQ(), r.getRevenueQ(),
                        netIncome(r.getNetIncomeOwnersQ(), r.getNetIncomeQ())));
            }
        }
        return new KrFinancialSnapshot(annuals, quarters, usable.isEmpty() ? null : usable.get(0), disclosed.size() - usable.size());
    }

    /** 단위 이상으로 뺀 보고서 수 */
    public int unitAnomalies() {
        return unitAnomalies;
    }

    private static Double medianTotalAssets(List<KrFinancialReportEntity> reports) {
        List<Long> assets = reports.stream().map(KrFinancialReportEntity::getTotalAssets).filter(a -> a != null && a > 0).sorted().toList();
        return assets.isEmpty() ? null : (double) assets.get(assets.size() / 2);
    }

    static boolean isUnitAnomaly(KrFinancialReportEntity r, Double medianAssets) {
        if (medianAssets == null || r.getTotalAssets() == null || r.getTotalAssets() <= 0) return false;
        double ratio = r.getTotalAssets() / medianAssets;
        return ratio > UNIT_ANOMALY_RATIO || ratio < 1 / UNIT_ANOMALY_RATIO;
    }

    /** 사업보고서 최신순 */
    public List<KrFinancialReportEntity> annuals() {
        return annuals;
    }

    public KrFinancialReportEntity latest() {
        return latest;
    }

    /** 가장 최근 분기부터 연속된 4개 분기 (최신순, 하나라도 비면 빈 목록) */
    public List<Quarter> lastFourQuarters() {
        if (quarters.isEmpty()) return List.of();
        List<Quarter> result = new ArrayList<>();
        YearMonth expected = YearMonth.from(quarters.lastKey());
        for (int i = 0; i < QUARTERS_PER_YEAR; i++) {
            Quarter q = quarters.get(expected.atEndOfMonth());
            if (q == null) return List.of();
            result.add(q);
            expected = expected.minusMonths(MONTHS_PER_QUARTER);
        }
        return result;
    }

    /** 최근 4개 분기 순이익 합 (분기가 비거나 값이 없으면 null) */
    public Long ttmNetIncome() {
        return sumOf(lastFourQuarters(), Quarter::netIncome);
    }

    public Long ttmRevenue() {
        return sumOf(lastFourQuarters(), Quarter::revenue);
    }

    /** 사업보고서 순이익 (지배주주 우선) */
    public static Long annualNetIncome(KrFinancialReportEntity annual) {
        return netIncome(annual.getNetIncomeOwnersYtd(), annual.getNetIncomeYtd());
    }

    private static Long netIncome(Long owners, Long total) {
        return owners != null ? owners : total;
    }

    private static Long sumOf(List<Quarter> qs, Function<Quarter, Long> field) {
        if (qs.size() < QUARTERS_PER_YEAR) return null;
        long sum = 0;
        for (Quarter q : qs) {
            Long v = field.apply(q);
            if (v == null) return null;
            sum += v;
        }
        return sum;
    }
}
