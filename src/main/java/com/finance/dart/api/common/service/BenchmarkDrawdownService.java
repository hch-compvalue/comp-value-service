package com.finance.dart.api.common.service;

import com.finance.dart.api.abroad.dto.fmp.chart.StockPriceVolumeReqDto;
import com.finance.dart.api.abroad.dto.fmp.chart.StockPriceVolumeResDto;
import com.finance.dart.api.abroad.service.fmp.StockPriceVolumeService;
import com.finance.dart.api.common.dto.evaluation.StockEvaluationResponse;
import com.finance.dart.api.domestic.enums.KrExchange;
import com.finance.dart.api.domestic.repository.KrPriceJdbcRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 기준 지수의 52주 고점 대비 하락률 — 종목 하락률을 시장과 비교해 보여주기 위한 표시용 (점수 계산 미사용)
 * - 하락장에서는 고정 기준(−20%/−30%) 경고가 거의 모든 종목에 떠서 구분이 안 됨 → 같은 기간 지수 대비 상대 하락률 제공
 * - 미국: SPY(FMP, 하루 1회 조회 후 캐시) / 국내: 종목 거래소의 종합지수(KOSPI·KOSDAQ, 저장된 지수 일봉)
 * - 기간은 종목 가격 이력과 같은 창(미국 1년, 국내 KrEvaluationService.HISTORY_DAYS)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BenchmarkDrawdownService {

    static final String US_SYMBOL = "SPY";
    static final String US_NAME = "S&P500(SPY)";
    private static final int US_FETCH_ATTEMPTS = 2;
    /** 캐시 상한 — 기간(from~to)별 키라 날짜가 바뀌면 새 키가 생김. 넘으면 비움 */
    private static final int CACHE_LIMIT = 64;
    private static final double PCT = 100.0;

    private final StockPriceVolumeService stockPriceVolumeService;
    private final KrPriceJdbcRepository krPriceRepository;

    private final Map<String, Drawdown> cache = new ConcurrentHashMap<>();

    /** 지수 이름 + 52주 고점 대비 하락률(%, 고점이면 0, 내려가면 음수) */
    public record Drawdown(String name, double dropPct) {}

    /** 미국 종목 응답에 SPY 대비 값 채우기 (종목 하락률·SPY 조회 실패 시 그대로) */
    public void applyUs(StockEvaluationResponse response, LocalDate from, LocalDate to) {
        apply(response, cached("US|" + from + "|" + to, () -> fetchUs(from, to)));
    }

    /** 국내 종목 응답에 거래소 종합지수 대비 값 채우기 */
    public void applyKr(StockEvaluationResponse response, String exchange, LocalDate from, LocalDate asOf) {
        KrExchange ex = KrExchange.KOSDAQ.name().equals(exchange) ? KrExchange.KOSDAQ : KrExchange.KOSPI;
        apply(response, cached("KR|" + ex + "|" + from + "|" + asOf, () -> loadKr(ex, from, asOf)));
    }

    /**
     * 지수 대비 상대 하락률(%) = (1 + 종목) / (1 + 지수) − 1
     * 0 = 시장과 같은 만큼 하락, 음수 = 시장보다 더 하락. 지수가 고점이면 종목 하락률과 같음
     */
    static double relativeDropPct(double stockDropPct, double benchDropPct) {
        return ((1 + stockDropPct / PCT) / (1 + benchDropPct / PCT) - 1) * PCT;
    }

    /** 고가 최댓값 대비 마지막 종가 하락률. 값이 없으면 null */
    static Double dropPct(double maxHigh, double lastClose) {
        if (maxHigh <= 0 || lastClose <= 0) return null;
        return (lastClose / maxHigh - 1) * PCT;
    }

    static void apply(StockEvaluationResponse response, Drawdown bench) {
        if (response == null || bench == null || response.getHigh52wDropPercent() == null) return;
        double stockDrop;
        try {
            stockDrop = Double.parseDouble(response.getHigh52wDropPercent().replace("%", ""));
        } catch (NumberFormatException e) {
            return;
        }
        response.setBenchmarkName(bench.name());
        response.setBenchmarkHigh52wDropPercent(formatPct(bench.dropPct()));
        response.setRelativeHigh52wDropPercent(formatPct(relativeDropPct(stockDrop, bench.dropPct())));
    }

    private static String formatPct(double v) {
        return String.format("%.2f%%", v);
    }

    private Drawdown cached(String key, Supplier<Drawdown> loader) {
        Drawdown hit = cache.get(key);
        if (hit != null) return hit;
        Drawdown loaded = loader.get();
        if (cache.size() >= CACHE_LIMIT) cache.clear();
        if (loaded != null) cache.put(key, loaded);     // 조회 실패(null)는 캐시하지 않음 → 다음 평가에서 재시도
        return loaded;
    }

    private Drawdown fetchUs(LocalDate from, LocalDate to) {
        StockPriceVolumeReqDto req = new StockPriceVolumeReqDto(US_SYMBOL, from.toString(), to.toString());
        for (int attempt = 1; attempt <= US_FETCH_ATTEMPTS; attempt++) {
            try {
                return toDrawdownUs(stockPriceVolumeService.findStockPriceVolume(req));
            } catch (RestClientException e) {
                log.warn("[52주 기준지수] {} 조회 실패 ({}/{}): {}", US_SYMBOL, attempt, US_FETCH_ATTEMPTS, e.getMessage());
            }
        }
        return null;
    }

    static Drawdown toDrawdownUs(List<StockPriceVolumeResDto> bars) {
        if (bars == null || bars.isEmpty()) return null;
        double maxHigh = bars.stream().map(b -> b.getHigh() != null ? b.getHigh() : b.getClose())
                .filter(Objects::nonNull).mapToDouble(Double::doubleValue).max().orElse(0);
        Double last = bars.stream().filter(b -> b.getDate() != null && b.getClose() != null)
                .max(Comparator.comparing(StockPriceVolumeResDto::getDate)).map(StockPriceVolumeResDto::getClose).orElse(null);
        Double drop = last == null ? null : dropPct(maxHigh, last);
        return drop == null ? null : new Drawdown(US_NAME, drop);
    }

    private Drawdown loadKr(KrExchange ex, LocalDate from, LocalDate asOf) {
        try {
            List<KrPriceJdbcRepository.IndexHighClose> bars = krPriceRepository.findIndexHighCloses(ex.getIndexCode(), from).stream()
                    .filter(b -> !b.tradeDate().isAfter(asOf)).toList();
            if (bars.isEmpty()) return null;
            double maxHigh = bars.stream().mapToDouble(KrPriceJdbcRepository.IndexHighClose::high).max().orElse(0);
            Double drop = dropPct(maxHigh, bars.get(bars.size() - 1).close());
            return drop == null ? null : new Drawdown(ex.name(), drop);
        } catch (DataAccessException e) {
            log.warn("[52주 기준지수] {} 조회 실패: {}", ex, e.getMessage());
            return null;
        }
    }
}
