package com.finance.dart.api.domestic.collect;

import com.finance.dart.api.domestic.kis.KisApi;
import com.finance.dart.api.domestic.kis.KisClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;

/**
 * KIS 기간별 시세 조회 (종목 일봉 1회 최대 100행, 지수 일봉 1회 최대 50행)
 * - 최신 → 과거 순으로 응답 → 가장 오래된 날짜 전날로 구간 끝을 옮기며 반복
 * - 종목은 수정주가(FID_ORG_ADJ_PRC=0)
 * - 결과는 날짜 오름차순, 날짜 중복 없음
 */
@Component
@RequiredArgsConstructor
public class KrDailyChartFetcher {

    static final int STOCK_PAGE_SIZE = 100;
    static final int INDEX_PAGE_SIZE = 50;
    /** 무한 반복 방지 (5년 = 종목 13회, 지수 25회) */
    static final int MAX_PAGES = 40;

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int YYYYMMDD_LENGTH = 8;
    private static final String MARKET_STOCK = "J";
    private static final String MARKET_INDEX = "U";
    private static final String PERIOD_DAY = "D";
    private static final String ADJUSTED_PRICE = "0";
    private static final String KEY_DATE = "stck_bsop_date";

    private final KisClient kisClient;

    public List<KrDailyBar> fetchStock(String stockCode, LocalDate from, LocalDate to) {
        return fetchPaged(KisApi.DAILY_CHART, STOCK_PAGE_SIZE, from, to, end -> Map.of(
                "FID_COND_MRKT_DIV_CODE", MARKET_STOCK,
                "FID_INPUT_ISCD", stockCode,
                "FID_INPUT_DATE_1", from.format(YYYYMMDD),
                "FID_INPUT_DATE_2", end.format(YYYYMMDD),
                "FID_PERIOD_DIV_CODE", PERIOD_DAY,
                "FID_ORG_ADJ_PRC", ADJUSTED_PRICE), KrDailyChartFetcher::toDailyBar);
    }

    public List<KrIndexBar> fetchIndex(String indexCode, LocalDate from, LocalDate to) {
        return fetchPaged(KisApi.INDEX_DAILY_CHART, INDEX_PAGE_SIZE, from, to, end -> Map.of(
                "FID_COND_MRKT_DIV_CODE", MARKET_INDEX,
                "FID_INPUT_ISCD", indexCode,
                "FID_INPUT_DATE_1", from.format(YYYYMMDD),
                "FID_INPUT_DATE_2", end.format(YYYYMMDD),
                "FID_PERIOD_DIV_CODE", PERIOD_DAY), KrDailyChartFetcher::toIndexBar);
    }

    private <T> List<T> fetchPaged(KisApi api, int pageSize, LocalDate from, LocalDate to,
                                   Function<LocalDate, Map<String, String>> params,
                                   Function<Map<String, Object>, T> mapper) {
        TreeMap<LocalDate, T> result = new TreeMap<>();
        LocalDate end = to;
        for (int page = 0; page < MAX_PAGES && !end.isBefore(from); page++) {
            List<Map<String, Object>> rows = output2(kisClient.get(api, params.apply(end)));
            LocalDate oldest = null;
            for (Map<String, Object> row : rows) {
                LocalDate date = parseDate(row.get(KEY_DATE));
                if (date == null || date.isBefore(from) || date.isAfter(to)) continue;
                T bar = mapper.apply(row);
                if (bar != null) result.put(date, bar);
                if (oldest == null || date.isBefore(oldest)) oldest = date;
            }
            // 빈 응답 = 구간에 더 이상 데이터 없음(상장 전 등), 한 페이지보다 적으면 구간을 다 받은 것
            if (oldest == null || rows.size() < pageSize) break;
            end = oldest.minusDays(1);
        }
        return new ArrayList<>(result.values());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> output2(Map<String, Object> body) {
        Object output = body.get("output2");
        return output instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    /** 가격이 비었거나 0 이하(데이터 이상)인 행은 버린다 */
    static KrDailyBar toDailyBar(Map<String, Object> row) {
        Integer open = toInt(row.get("stck_oprc"));
        Integer high = toInt(row.get("stck_hgpr"));
        Integer low = toInt(row.get("stck_lwpr"));
        Integer close = toInt(row.get("stck_clpr"));
        if (open == null || high == null || low == null || close == null || close <= 0) return null;
        return new KrDailyBar(parseDate(row.get(KEY_DATE)), open, high, low, close,
                toLong(row.get("acml_vol")), toLong(row.get("acml_tr_pbmn")));
    }

    static KrIndexBar toIndexBar(Map<String, Object> row) {
        BigDecimal open = toDecimal(row.get("bstp_nmix_oprc"));
        BigDecimal high = toDecimal(row.get("bstp_nmix_hgpr"));
        BigDecimal low = toDecimal(row.get("bstp_nmix_lwpr"));
        BigDecimal close = toDecimal(row.get("bstp_nmix_prpr"));
        if (open == null || high == null || low == null || close == null || close.signum() <= 0) return null;
        return new KrIndexBar(parseDate(row.get(KEY_DATE)), open, high, low, close);
    }

    private static LocalDate parseDate(Object value) {
        String text = value == null ? "" : value.toString().trim();
        if (text.length() != YYYYMMDD_LENGTH) return null;
        try {
            return LocalDate.parse(text, YYYYMMDD);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Integer toInt(Object value) {
        BigDecimal decimal = toDecimal(value);
        return decimal == null ? null : decimal.intValue();
    }

    private static long toLong(Object value) {
        BigDecimal decimal = toDecimal(value);
        return decimal == null ? 0L : decimal.longValue();
    }

    private static BigDecimal toDecimal(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        try {
            return new BigDecimal(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
