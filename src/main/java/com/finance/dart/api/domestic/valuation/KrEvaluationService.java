package com.finance.dart.api.domestic.valuation;

import com.finance.dart.api.abroad.dto.fmp.chart.StockPriceVolumeResDto;
import com.finance.dart.api.abroad.dto.fmp.quote.StockQuoteResDto;
import com.finance.dart.api.common.dto.evaluation.EvaluationInputs;
import com.finance.dart.api.common.dto.evaluation.StockEvaluationResponse;
import com.finance.dart.api.common.service.BenchmarkDrawdownService;
import com.finance.dart.api.common.service.StockEvaluationService;
import com.finance.dart.api.domestic.collect.KrDailyBar;
import com.finance.dart.api.domestic.repository.KrPriceJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 국내 6단계 평가 (ROADMAP_domestic 4단계) — 미국과 같은 점수 계산(StockEvaluationService.evaluate)에 국내 입력을 넘긴다
 * - 시세·1년 가격 이력: 우리 일봉(전 거래일까지 확정 종가) → FMP DTO 형태로 변환 (최신순)
 * - Forward PER: 국내는 애널리스트 추정치가 거의 없어 생략 (감점 없음)
 * - 품질 게이트: 초소형주 기준 시총 1,000억 원, 지주회사는 매수후보에서 제외(관심목록) — 11-02 사용자 확정
 */
@Service
@RequiredArgsConstructor
public class KrEvaluationService {

    /** 국내 초소형주 기준 (원) */
    public static final long MICROCAP_KRW = 100_000_000_000L;
    static final int HISTORY_DAYS = 400;          // 달력일 — 1년치(약 250거래일) 확보
    static final String COUNTRY = "KR";
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final long JO = 1_000_000_000_000L;
    private static final long EOK = 100_000_000L;
    private static final String NA = "N/A";

    private final KrValuationService valuationService;
    private final StockEvaluationService stockEvaluationService;
    private final KrPriceJdbcRepository priceRepository;
    private final BenchmarkDrawdownService benchmarkDrawdownService;   // 52주 하락률 시장 비교(표시용)

    /** 평가 결과 (계산 제외 종목은 response 없이 사유만) */
    public record Result(StockEvaluationResponse response, String excludedReason, Double roe) {}

    public Result evaluate(String stockCode) {
        return evaluate(valuationService.compute(stockCode));
    }

    /** 이미 계산한 결과로 평가 (추천 배치 — 1차 필터에서 계산한 결과 재사용) */
    public Result evaluate(KrValuationService.Valuation v) {
        return evaluate(v, LocalDate.now(ZONE));
    }

    /** 기준일 시점 평가 (백테스트) — 기준일 이후 일봉은 쓰지 않는다 */
    public Result evaluate(KrValuationService.Valuation v, LocalDate asOf) {
        if (v.excluded()) return new Result(null, v.input().excluded(), null);

        KrValuationInputBuilder.Input in = v.input();
        String stockCode = in.stock().getStockCode();
        List<KrDailyBar> bars = priceRepository.findDailyBars(stockCode, asOf.minusDays(HISTORY_DAYS)).stream()
                .filter(b -> !b.tradeDate().isAfter(asOf)).toList();
        long marketCap = BigDecimal.valueOf(in.price()).multiply(BigDecimal.valueOf(in.stock().getListedShares()))
                .setScale(0, RoundingMode.HALF_UP).longValue();

        EvaluationInputs inputs = new EvaluationInputs(v.result(),
                v.result().get섹터(),
                in.stock().getKsicName() != null ? in.stock().getKsicName() : NA,
                in.beta(),
                in.stock().getExchange(),
                COUNTRY,
                marketCap,
                formatMarketCap(marketCap),
                averageVolume(bars),
                quote(in, bars),
                history(stockCode, bars),
                null,
                MICROCAP_KRW,
                in.sector().holdingCompany());
        StockEvaluationResponse response = stockEvaluationService.evaluate(stockCode, inputs);
        benchmarkDrawdownService.applyKr(response, in.stock().getExchange(), asOf.minusDays(HISTORY_DAYS), asOf);
        return new Result(response, null, in.metrics().roe());
    }

    /** 현재가(전 거래일 종가)·SMA50·SMA200·거래량 */
    static StockQuoteResDto quote(KrValuationInputBuilder.Input in, List<KrDailyBar> bars) {
        StockQuoteResDto q = new StockQuoteResDto();
        q.setPrice(in.price());
        if (in.detail().getSMA50() != null && !NA.equals(in.detail().getSMA50())) q.setPriceAvg50(Double.valueOf(in.detail().getSMA50()));
        if (in.detail().getSMA200() != null && !NA.equals(in.detail().getSMA200())) q.setPriceAvg200(Double.valueOf(in.detail().getSMA200()));
        if (!bars.isEmpty()) q.setVolume(bars.get(bars.size() - 1).volume());
        return q;
    }

    /** 일봉 → FMP 가격 이력 형태 (최신순 — 기술적 분석이 첫 원소를 현재가로 본다) */
    static List<StockPriceVolumeResDto> history(String stockCode, List<KrDailyBar> bars) {
        List<StockPriceVolumeResDto> list = new ArrayList<>(bars.size());
        for (int i = bars.size() - 1; i >= 0; i--) {
            KrDailyBar b = bars.get(i);
            StockPriceVolumeResDto d = new StockPriceVolumeResDto();
            d.setSymbol(stockCode);
            d.setDate(b.tradeDate().toString());
            d.setOpen((double) b.open());
            d.setHigh((double) b.high());
            d.setLow((double) b.low());
            d.setClose((double) b.close());
            d.setVolume(b.volume());
            list.add(d);
        }
        return list;
    }

    static Long averageVolume(List<KrDailyBar> bars) {
        return bars.isEmpty() ? null : Math.round(bars.stream().mapToLong(KrDailyBar::volume).average().orElse(0));
    }

    /** 1.2조원 / 3,450억원 */
    static String formatMarketCap(long krw) {
        if (krw >= JO) return String.format("%.1f조원", krw / (double) JO);
        return String.format("%,d억원", krw / EOK);
    }

}
