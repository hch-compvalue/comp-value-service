package com.finance.dart.api.domestic.service;

import com.finance.dart.api.abroad.dto.fmp.chart.HistoricalIndexResDto;
import com.finance.dart.api.domestic.collect.KrDailyChartFetcher;
import com.finance.dart.api.domestic.collect.KrIndexBar;
import com.finance.dart.api.domestic.enums.KrExchange;
import com.finance.dart.api.domestic.kis.KisApiException;
import com.finance.dart.api.domestic.repository.KrPriceJdbcRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 국내 지수(KOSPI·KOSDAQ) 일봉 — 홈 "국내 주요 지수" 차트 (미국 /abroad/chart/historical-index 와 같은 응답 형식)
 * - 과거: 국내 일봉 수집(KR_DAILY_PRICE, 평일 16시)이 저장한 tb_kr_index_daily
 * - 마지막 저장일 다음 날 ~ 오늘: KIS 업종 기간별시세로 보충 (장중이면 오늘 값 포함), 지수별 5분 캐시
 * - KIS 실패·키 미설정이면 저장분만 반환
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KrIndexChartService {

    static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    /** KIS 보충 결과 재사용 시간 — 홈을 열 때마다 KIS 를 부르지 않도록 */
    static final Duration RECENT_CACHE_TTL = Duration.ofMinutes(5);
    /** 수집이 오래 밀려도 KIS 보충은 최근 이 기간만 (호출 수 제한, 지수 50행/회) */
    static final int MAX_FILL_DAYS = 60;
    private static final int PERCENT = 100;

    private final KrPriceJdbcRepository priceRepository;
    private final KrDailyChartFetcher fetcher;
    /** 테스트에서 날짜·시각 고정용 (빈 아님) */
    Clock clock = Clock.system(ZONE);

    private record CachedBars(LocalDate after, Instant fetchedAt, List<KrIndexBar> bars) {}

    private final Map<KrExchange, CachedBars> recentCache = new ConcurrentHashMap<>();

    /** @param from 조회 시작일 (포함) */
    public List<HistoricalIndexResDto> chart(KrExchange exchange, LocalDate from) {
        TreeMap<LocalDate, KrIndexBar> merged = new TreeMap<>();
        priceRepository.findIndexBars(exchange.getIndexCode(), from).forEach(b -> merged.put(b.tradeDate(), b));
        LocalDate lastStored = merged.isEmpty() ? null : merged.lastKey();
        recentBars(exchange, lastStored).stream()
                .filter(b -> !b.tradeDate().isBefore(from))
                .forEach(b -> merged.put(b.tradeDate(), b));
        return toResponse(exchange, merged);
    }

    /** 마지막 저장일 이후 ~ 오늘 (저장분이 없으면 보충 안 함 — 첫 적재는 수집 작업 몫) */
    List<KrIndexBar> recentBars(KrExchange exchange, LocalDate lastStored) {
        LocalDate today = LocalDate.now(clock.withZone(ZONE));
        if (lastStored == null || !lastStored.isBefore(today)) return List.of();
        CachedBars cached = recentCache.get(exchange);
        Instant now = clock.instant();
        if (cached != null && cached.after().equals(lastStored)
                && Duration.between(cached.fetchedAt(), now).compareTo(RECENT_CACHE_TTL) < 0) {
            return cached.bars();
        }
        LocalDate fillFrom = lastStored.plusDays(1);
        LocalDate earliest = today.minusDays(MAX_FILL_DAYS);
        if (fillFrom.isBefore(earliest)) fillFrom = earliest;
        try {
            List<KrIndexBar> bars = fetcher.fetchIndex(exchange.getIndexCode(), fillFrom, today);
            recentCache.put(exchange, new CachedBars(lastStored, now, bars));
            return bars;
        } catch (KisApiException e) {
            log.warn("[국내 지수 차트] {} KIS 보충 실패 → 저장분만 표시: {}", exchange, e.getMessage());
            return List.of();
        }
    }

    /** 날짜 오름차순, 전 거래일 종가 대비 등락 (첫 봉은 null) */
    static List<HistoricalIndexResDto> toResponse(KrExchange exchange, TreeMap<LocalDate, KrIndexBar> bars) {
        List<HistoricalIndexResDto> result = new ArrayList<>(bars.size());
        BigDecimal prevClose = null;
        for (KrIndexBar bar : bars.values()) {
            HistoricalIndexResDto dto = new HistoricalIndexResDto();
            dto.setSymbol(exchange.name());
            dto.setDate(bar.tradeDate().toString());
            dto.setOpen(bar.open().doubleValue());
            dto.setHigh(bar.high().doubleValue());
            dto.setLow(bar.low().doubleValue());
            dto.setClose(bar.close().doubleValue());
            if (prevClose != null && prevClose.signum() > 0) {
                double change = bar.close().subtract(prevClose).doubleValue();
                dto.setChange(change);
                dto.setChangePercent(change / prevClose.doubleValue() * PERCENT);
            }
            result.add(dto);
            prevClose = bar.close();
        }
        return result;
    }
}
