package com.finance.dart.api.domestic.service;

import com.finance.dart.api.abroad.dto.fmp.chart.StockPriceVolumeResDto;
import com.finance.dart.api.domestic.collect.KrDailyBar;
import com.finance.dart.api.domestic.dto.KrStockProfile;
import com.finance.dart.api.domestic.dto.KrStockSearchItem;
import com.finance.dart.api.domestic.entity.KrStockEntity;
import com.finance.dart.api.domestic.repository.KrPriceJdbcRepository;
import com.finance.dart.api.domestic.repository.KrStockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 국내 종목 조회 (ROADMAP_domestic 5-B 종목 분석) — 저장된 종목 마스터·일봉만 읽는다 (외부 호출 없음)
 */
@Service
@RequiredArgsConstructor
public class KrStockQueryService {

    static final int SEARCH_LIMIT = 50;
    static final int RANGE_DAYS = 365;            // 52주 범위 (달력일)
    static final int AVG_VOLUME_BARS = 60;        // 평균 거래량 (최근 거래일 수, 약 3개월)
    static final String CURRENCY = "KRW";
    static final String COUNTRY = "KR";
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final String LIKE_WILDCARDS = "[%_\\\\]";

    private final KrStockRepository stockRepository;
    private final KrPriceJdbcRepository priceRepository;

    /** 종목명 일부 또는 코드 앞부분 (활성 종목만) */
    public List<KrStockSearchItem> search(String query) {
        String q = query == null ? "" : query.trim().replaceAll(LIKE_WILDCARDS, "");
        if (q.isEmpty()) return List.of();
        return stockRepository.search(q, PageRequest.of(0, SEARCH_LIMIT)).stream()
                .map(s -> new KrStockSearchItem(s.getStockCode(), s.getStockName(), CURRENCY, s.getExchange(), s.getExchange()))
                .toList();
    }

    /** 기업정보 — 종목이 없으면 empty */
    public Optional<KrStockProfile> profile(String stockCode) {
        return stockRepository.findById(stockCode).map(s -> toProfile(s,
                priceRepository.findDailyBars(stockCode, LocalDate.now(ZONE).minusDays(RANGE_DAYS))));
    }

    /** 일봉 (미국 차트 응답과 같은 형식, 날짜 오름차순) — 전일 대비는 조회 구간 첫날 이전 값이 없으면 null */
    public List<StockPriceVolumeResDto> chart(String stockCode, LocalDate from, LocalDate to) {
        List<KrDailyBar> bars = priceRepository.findDailyBars(stockCode, from);
        List<StockPriceVolumeResDto> list = new ArrayList<>(bars.size());
        KrDailyBar prev = null;
        for (KrDailyBar b : bars) {
            if (to != null && b.tradeDate().isAfter(to)) break;
            list.add(toChartRow(stockCode, b, prev));
            prev = b;
        }
        return list;
    }

    static KrStockProfile toProfile(KrStockEntity s, List<KrDailyBar> bars) {
        KrStockProfile p = new KrStockProfile();
        p.setSymbol(s.getStockCode());
        p.setCompanyName(s.getStockName());
        p.setExchange(s.getExchange());
        p.setExchangeFullName(s.getExchange());
        p.setCurrency(CURRENCY);
        p.setCountry(COUNTRY);
        p.setSector(s.getKsicName());
        p.setIndustry(s.getKsicName());
        p.setIpoDate(s.getListedDate() != null ? s.getListedDate().toString() : null);
        p.setListedShares(s.getListedShares());
        p.setFiscalMonth(s.getFiscalMonth());
        p.setAdministrative(s.getAdministrative());
        p.setTradingHalt(s.getTradingHalt());
        if (bars.isEmpty()) return p;

        KrDailyBar last = bars.get(bars.size() - 1);
        p.setPrice((double) last.close());
        p.setPriceDate(last.tradeDate().toString());
        if (bars.size() > 1) {
            int prevClose = bars.get(bars.size() - 2).close();
            p.setChange((double) (last.close() - prevClose));
            p.setChangePercentage(changePercent(last.close(), prevClose));
        }
        if (s.getListedShares() != null) p.setMarketCap((long) last.close() * s.getListedShares());
        int low = bars.stream().mapToInt(KrDailyBar::low).min().orElse(0);
        int high = bars.stream().mapToInt(KrDailyBar::high).max().orElse(0);
        p.setRange(String.format("%,d-%,d", low, high));
        p.setAverageVolume(Math.round(bars.subList(Math.max(0, bars.size() - AVG_VOLUME_BARS), bars.size()).stream()
                .mapToLong(KrDailyBar::volume).average().orElse(0)));
        return p;
    }

    static StockPriceVolumeResDto toChartRow(String stockCode, KrDailyBar b, KrDailyBar prev) {
        StockPriceVolumeResDto d = new StockPriceVolumeResDto();
        d.setSymbol(stockCode);
        d.setDate(b.tradeDate().toString());
        d.setOpen((double) b.open());
        d.setHigh((double) b.high());
        d.setLow((double) b.low());
        d.setClose((double) b.close());
        d.setVolume(b.volume());
        if (b.volume() > 0) d.setVwap(BigDecimal.valueOf(b.tradeAmount()).divide(BigDecimal.valueOf(b.volume()), 2, RoundingMode.HALF_UP).doubleValue());
        if (prev != null) {
            d.setChange((double) (b.close() - prev.close()));
            d.setChangePercent(changePercent(b.close(), prev.close()));
        }
        return d;
    }

    static Double changePercent(int close, int prevClose) {
        if (prevClose == 0) return null;
        return BigDecimal.valueOf((close - prevClose) * 100.0 / prevClose).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
