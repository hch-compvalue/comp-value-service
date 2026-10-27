package com.finance.dart.api.domestic.collect;

import com.finance.dart.api.domestic.repository.KrPriceJdbcRepository;
import com.finance.dart.api.domestic.repository.KrPriceJdbcRepository.LastBar;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * 국내 일봉·지수 수집 (종목 1개 / 지수 1개 단위)
 * - 처음: 5년치 전체, 이후: 마지막 저장일부터 증분
 * - 수정주가 변경 감지: 증분 조회에 포함된 마지막 저장일 종가가 저장값과 다르면(액면분할·증자 등) 5년치를 다시 받아 교체
 *   → 그대로 이어 붙이면 과거·최근 가격 기준이 섞여 수익률·베타가 조용히 틀린다
 */
@Service
@RequiredArgsConstructor
public class KrDailyPriceCollector {

    static final int HISTORY_YEARS = 5;
    /** 장 마감(15:30) 후 종가 확정 시각 — 이전에 실행하면 전날까지만 저장 (장중 가격이 종가로 저장되는 것 방지) */
    static final LocalTime CLOSE_SETTLED_AT = LocalTime.of(15, 40);

    public enum Outcome { FULL, INCREMENTAL, READJUSTED, UP_TO_DATE }

    private final KrDailyChartFetcher fetcher;
    private final KrPriceJdbcRepository priceRepository;

    /** 저장 대상 마지막 날짜 */
    public static LocalDate settledDate(LocalDateTime now) {
        return now.toLocalTime().isBefore(CLOSE_SETTLED_AT) ? now.toLocalDate().minusDays(1) : now.toLocalDate();
    }

    /**
     * @param last 마지막 저장 일봉 (없으면 null = 첫 적재)
     */
    public Outcome collectStock(String stockCode, LastBar last, LocalDate settled) {
        LocalDate historyStart = settled.minusYears(HISTORY_YEARS);
        if (last == null) {
            List<KrDailyBar> bars = fetcher.fetchStock(stockCode, historyStart, settled);
            if (!bars.isEmpty()) priceRepository.upsertDaily(stockCode, bars);
            return Outcome.FULL;
        }
        if (!last.tradeDate().isBefore(settled)) return Outcome.UP_TO_DATE;

        List<KrDailyBar> bars = fetcher.fetchStock(stockCode, last.tradeDate(), settled);
        if (isAdjusted(bars, last)) {
            List<KrDailyBar> full = fetcher.fetchStock(stockCode, historyStart, settled);
            if (full.isEmpty()) throw new KrCollectException(stockCode + " 수정주가 재수집 결과가 비어 있음 — 기존 일봉 유지");
            priceRepository.replaceDaily(stockCode, full);
            return Outcome.READJUSTED;
        }

        List<KrDailyBar> newBars = bars.stream().filter(b -> b.tradeDate().isAfter(last.tradeDate())).toList();
        if (!newBars.isEmpty()) priceRepository.upsertDaily(stockCode, newBars);
        return Outcome.INCREMENTAL;
    }

    /** 지수는 수정이 없어 증분만 (마지막 저장일 포함 재조회 → upsert) */
    public int collectIndex(String indexCode, LocalDate settled) {
        LocalDate last = priceRepository.findLastIndexDate(indexCode);
        if (last != null && !last.isBefore(settled)) return 0;
        LocalDate from = last == null ? settled.minusYears(HISTORY_YEARS) : last;
        List<KrIndexBar> bars = fetcher.fetchIndex(indexCode, from, settled);
        if (!bars.isEmpty()) priceRepository.upsertIndex(indexCode, bars);
        return bars.size();
    }

    static boolean isAdjusted(List<KrDailyBar> bars, LastBar last) {
        Optional<KrDailyBar> sameDay = bars.stream().filter(b -> b.tradeDate().equals(last.tradeDate())).findFirst();
        return sameDay.isPresent() && sameDay.get().close() != last.closePrice();
    }
}
