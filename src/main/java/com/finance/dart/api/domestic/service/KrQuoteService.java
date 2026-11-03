package com.finance.dart.api.domestic.service;

import com.finance.dart.api.domestic.kis.KisApi;
import com.finance.dart.api.domestic.kis.KisApiException;
import com.finance.dart.api.domestic.kis.KisClient;
import com.finance.dart.api.domestic.repository.KrPriceJdbcRepository;
import com.finance.dart.api.domestic.valuation.KrPriceSeries;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 국내 현재가 (ROADMAP_domestic 5-C 포트폴리오) — KIS 현재가 시세, 실패하면 저장된 최근 종가
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KrQuoteService {

    static final String MARKET_DIV_STOCK = "J";          // KIS 시장 구분: 주식
    static final int FALLBACK_LOOKBACK_DAYS = 14;        // 저장 종가 대체 조회 범위 (달력일, 연휴 포함)

    private final KisClient kisClient;
    private final KrPriceJdbcRepository priceRepository;

    /**
     * @param price         현재가 (없으면 null)
     * @param previousClose 전일 종가
     * @param live          true: KIS 실시간, false: 저장된 종가로 대체
     */
    public record Quote(Double price, Double previousClose, boolean live) {}

    /** 종목코드별 현재가 (조회 순서 유지) — KIS 한도는 KisClient 가 지킨다 */
    public Map<String, Quote> currentPrices(Collection<String> stockCodes) {
        Map<String, Quote> result = new LinkedHashMap<>();
        for (String code : stockCodes) result.put(code, quote(code));
        return result;
    }

    Quote quote(String code) {
        try {
            Map<String, Object> body = kisClient.get(KisApi.PRICE, Map.of("FID_COND_MRKT_DIV_CODE", MARKET_DIV_STOCK, "FID_INPUT_ISCD", code));
            Map<?, ?> output = body.get("output") instanceof Map<?, ?> m ? m : Map.of();
            Double price = positive(output.get("stck_prpr"));
            if (price != null) return new Quote(price, positive(output.get("stck_sdpr")), true);
            log.warn("[국내 현재가] {} KIS 응답에 현재가 없음 → 저장 종가 사용", code);
        } catch (KisApiException e) {
            log.warn("[국내 현재가] {} KIS 실패 → 저장 종가 사용: {}", code, e.getMessage());
        }
        return storedClose(code);
    }

    /** 저장된 최근 종가 (전일 종가는 그 전 거래일) */
    Quote storedClose(String code) {
        List<KrPriceSeries.DailyClose> closes = priceRepository.findDailyCloses(code, LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(FALLBACK_LOOKBACK_DAYS));
        if (closes.isEmpty()) return new Quote(null, null, false);
        Double last = closes.get(closes.size() - 1).close();
        Double prev = closes.size() > 1 ? closes.get(closes.size() - 2).close() : null;
        return new Quote(last, prev, false);
    }

    static Double positive(Object v) {
        if (v == null) return null;
        try {
            double d = Double.parseDouble(v.toString().trim());
            return d > 0 ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
