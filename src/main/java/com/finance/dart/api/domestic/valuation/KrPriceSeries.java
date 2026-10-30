package com.finance.dart.api.domestic.valuation;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * 일봉 종가 시계열 (날짜 오름차순) — 현재가·이동평균·결산기 말 종가·베타
 * - 베타: 월말 종가 월간 수익률로 시장지수(KOSPI) 대비 회귀 기울기, 최근 60개월(최소 24개월)
 *   (미국 V9가 쓰는 FMP 베타가 5년 월간 기준이라 같은 방식)
 */
public final class KrPriceSeries {

    static final int BETA_MONTHS = 60;
    static final int BETA_MIN_MONTHS = 24;
    /** 결산기 말 종가: 기간 종료일 이전 이 일수 안의 마지막 거래일 (연휴·거래정지 대비) */
    static final int PERIOD_END_LOOKBACK_DAYS = 10;

    /** 하루 종가 */
    public record DailyClose(LocalDate date, double close) {}

    private final List<DailyClose> closes;

    private KrPriceSeries(List<DailyClose> closes) {
        this.closes = closes;
    }

    public static KrPriceSeries of(List<DailyClose> closes) {
        List<DailyClose> sorted = new ArrayList<>(closes);
        sorted.sort(Comparator.comparing(DailyClose::date));
        return new KrPriceSeries(sorted);
    }

    /** 기준일까지만 (백테스트에서 미래 가격 제외) */
    public KrPriceSeries upTo(LocalDate asOf) {
        return new KrPriceSeries(closes.stream().filter(c -> !c.date().isAfter(asOf)).toList());
    }

    public boolean isEmpty() {
        return closes.isEmpty();
    }

    public DailyClose last() {
        return closes.isEmpty() ? null : closes.get(closes.size() - 1);
    }

    /** 최근 n거래일 평균 (부족하면 null) */
    public Double sma(int n) {
        if (closes.size() < n) return null;
        double sum = 0;
        for (int i = closes.size() - n; i < closes.size(); i++) sum += closes.get(i).close();
        return sum / n;
    }

    /** date 이전(포함) PERIOD_END_LOOKBACK_DAYS 안의 마지막 종가 (없으면 null — 상장 전 등) */
    public Double closeOnOrBefore(LocalDate date) {
        for (int i = closes.size() - 1; i >= 0; i--) {
            DailyClose c = closes.get(i);
            if (c.date().isAfter(date)) continue;
            return c.date().isBefore(date.minusDays(PERIOD_END_LOOKBACK_DAYS)) ? null : c.close();
        }
        return null;
    }

    /** 월말 종가 (그달 마지막 거래일) */
    SortedMap<YearMonth, Double> monthEndCloses() {
        SortedMap<YearMonth, Double> map = new TreeMap<>();
        for (DailyClose c : closes) map.put(YearMonth.from(c.date()), c.close());
        return map;
    }

    /** 시장 대비 베타 (같은 달끼리 짝지은 월간 수익률이 BETA_MIN_MONTHS 미만이면 null) */
    public static Double beta(KrPriceSeries stock, KrPriceSeries market) {
        SortedMap<YearMonth, Double> s = stock.monthEndCloses();
        SortedMap<YearMonth, Double> m = market.monthEndCloses();
        if (!s.isEmpty() && isMonthInProgress(stock.last().date())) s.remove(s.lastKey());   // 진행 중인 달은 월말 종가가 아님
        List<double[]> pairs = new ArrayList<>();
        YearMonth prev = null;
        for (YearMonth ym : s.keySet()) {
            if (prev != null && prev.plusMonths(1).equals(ym) && m.containsKey(ym) && m.containsKey(prev)) {
                pairs.add(new double[]{s.get(ym) / s.get(prev) - 1, m.get(ym) / m.get(prev) - 1});
            }
            prev = ym;
        }
        if (pairs.size() > BETA_MONTHS) pairs = pairs.subList(pairs.size() - BETA_MONTHS, pairs.size());
        if (pairs.size() < BETA_MIN_MONTHS) return null;

        double meanS = 0, meanM = 0;
        for (double[] p : pairs) { meanS += p[0]; meanM += p[1]; }
        meanS /= pairs.size();
        meanM /= pairs.size();
        double cov = 0, var = 0;
        for (double[] p : pairs) {
            cov += (p[0] - meanS) * (p[1] - meanM);
            var += (p[1] - meanM) * (p[1] - meanM);
        }
        return var == 0 ? null : cov / var;
    }

    /** 마지막 거래일이 달력상 말일 전이면 진행 중인 달로 본다 (말일이 주말인 달은 한 달 덜 쓰지만 보수적) */
    private static boolean isMonthInProgress(LocalDate lastDate) {
        return lastDate.isBefore(YearMonth.from(lastDate).atEndOfMonth());
    }
}
