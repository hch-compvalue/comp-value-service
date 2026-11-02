package com.finance.dart.api.domestic.valuation;

import com.finance.dart.api.abroad.dto.fmp.ratiosttm.RatiosTtmResDto;
import com.finance.dart.api.common.constants.Market;
import com.finance.dart.api.common.context.SimpleRequestAttributes;
import com.finance.dart.api.common.dto.evaluation.StockEvaluationResponse;
import com.finance.dart.api.common.entity.RecommendProfileConfigEntity;
import com.finance.dart.api.common.entity.RecommendProfileEntity;
import com.finance.dart.api.common.repository.RecommendProfileConfigRepository;
import com.finance.dart.api.common.repository.RecommendProfileRepository;
import com.finance.dart.api.common.service.EvaluationHistoryService;
import com.finance.dart.api.common.service.RecommendHistoryService;
import com.finance.dart.api.common.service.schedule.RecommendedStocksProcessor;
import com.finance.dart.api.common.service.schedule.RecommendedStocksProcessor.RecommendedStockData;
import com.finance.dart.api.domestic.entity.KrStockEntity;
import com.finance.dart.api.domestic.repository.KrStockRepository;
import com.finance.dart.common.util.DateUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.*;
import java.util.function.Supplier;

/**
 * 국내 추천 → 6단계 평가 (ROADMAP_domestic 4단계, KR_RECOMMEND 배치)
 * 1) 활성 종목(관리종목·거래정지 제외) 전부 국내 V9 계산 — 외부 API 호출 없음(우리 DB)
 * 2) 국내 프로파일(market=KR)별 1차 필터 — 미국과 같은 판정 함수(isUndervalued·isGrowthSatisfied)·섹터 비중 상한 재사용
 * 3) 추천 이력 저장 → 후보 6단계 평가 → 평가 이력(market=KR) 저장(투자 순위 점수 포함)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KrRecommendService {

    private static final String ACTIVE = "Y";
    private static final String FLAG_YES = "Y";

    private final RecommendProfileRepository profileRepository;
    private final RecommendProfileConfigRepository configRepository;
    private final KrStockRepository stockRepository;
    private final KrValuationService valuationService;
    private final KrEvaluationService evaluationService;
    private final RecommendedStocksProcessor recommendedStocksProcessor;
    private final RecommendHistoryService recommendHistoryService;
    private final EvaluationHistoryService evaluationHistoryService;

    /** 실행 결과 */
    public record Summary(int stocks, int calculated, int excluded, int failed, Map<String, Integer> perProfile,
                          int candidates, int saved) {
        public String text() {
            return String.format("종목 %d (계산 %d, 제외 %d, 실패 %d) / 프로파일 %s / 후보 %d / 평가 저장 %d",
                    stocks, calculated, excluded, failed, perProfile, candidates, saved);
        }
    }

    /**
     * @param maxCount 계산할 종목 수 제한 (0 = 전체)
     */
    public Summary run(int maxCount) {
        List<RecommendProfileEntity> profiles = profileRepository.findByIsActiveAndMarketOrderBySortOrder(ACTIVE, Market.KR);
        if (profiles.isEmpty()) {
            log.warn("[국내 추천] 활성 국내 프로파일 없음 — 건너뜀");
            return new Summary(0, 0, 0, 0, Map.of(), 0, 0);
        }

        List<KrStockEntity> stocks = stockRepository.findByActiveOrderByStockCode(ACTIVE).stream()
                .filter(s -> !FLAG_YES.equals(s.getTradingHalt()) && !FLAG_YES.equals(s.getAdministrative()))
                .toList();
        if (maxCount > 0 && stocks.size() > maxCount) stocks = stocks.subList(0, maxCount);

        Map<String, KrValuationService.Valuation> valuations = new LinkedHashMap<>();
        int excluded = 0, failed = 0;
        for (KrStockEntity s : stocks) {
            try {
                KrValuationService.Valuation v = inRequestScope(() -> valuationService.compute(s.getStockCode()));
                if (v.excluded()) excluded++;
                else valuations.put(s.getStockCode(), v);
            } catch (DataAccessException | IllegalStateException | NumberFormatException | ArithmeticException e) {
                failed++;     // 한 종목 데이터 이상이 전체 배치를 멈추지 않게
                log.warn("[국내 추천] {} 계산 실패: {}", s.getStockCode(), e.getMessage());
            }
        }

        Map<String, List<String>> symbolProfiles = new LinkedHashMap<>();
        Map<String, Double> symbolRoe = new HashMap<>();
        Map<String, Integer> perProfile = new LinkedHashMap<>();
        for (RecommendProfileEntity profile : profiles) {
            RecommendProfileConfigEntity config = configRepository.findByProfile_Id(profile.getId()).orElse(null);
            if (config == null) continue;
            Map<String, RecommendedStockData> picked = new LinkedHashMap<>();
            for (KrValuationService.Valuation v : valuations.values()) {
                if (passes(v.input(), config)) picked.put(v.input().stock().getStockCode(), toData(v.input()));
            }
            picked = RecommendedStocksProcessor.applySectorQuota(picked, config.getSectorQuotaPct());
            recommendHistoryService.saveSnapshot(profile.getProfileName(), picked, Market.KR);
            perProfile.put(profile.getProfileName(), picked.size());
            for (RecommendedStockData d : picked.values()) {
                List<String> tags = symbolProfiles.computeIfAbsent(d.symbol(), k -> new ArrayList<>());
                if (!tags.contains(profile.getProfileName())) tags.add(profile.getProfileName());
                if (d.returnOnEquityTTM() != null) symbolRoe.putIfAbsent(d.symbol(), d.returnOnEquityTTM());
            }
        }

        List<StockEvaluationResponse> responses = new ArrayList<>();
        for (String code : symbolProfiles.keySet()) {
            KrEvaluationService.Result r = inRequestScope(() -> evaluationService.evaluate(valuations.get(code)));
            if (r.response() != null) responses.add(r.response());
        }
        int saved = evaluationHistoryService.saveSnapshot(Market.KR, responses, symbolProfiles, symbolRoe);
        Summary summary = new Summary(stocks.size(), valuations.size(), excluded, failed, perProfile, symbolProfiles.size(), saved);
        log.info("[국내 추천] {}", summary.text());
        return summary;
    }

    /** 프로파일 조건 (미국 스크리너 조건 중 국내에 해당하는 것 + 미국과 같은 저평가·성장률 판정) */
    boolean passes(KrValuationInputBuilder.Input in, RecommendProfileConfigEntity config) {
        KrValuationInputBuilder.ScreeningMetrics m = in.metrics();
        if (config.getMarketCapMin() != null && m.marketCap() < config.getMarketCapMin()) return false;
        if (config.getMarketCapMax() != null && m.marketCap() > config.getMarketCapMax()) return false;
        if (config.getPriceMin() != null && in.price() < config.getPriceMin().doubleValue()) return false;
        if (config.getPriceMax() != null && in.price() > config.getPriceMax().doubleValue()) return false;
        if (config.getBetaMin() != null && (in.beta() == null || in.beta() < config.getBetaMin().doubleValue())) return false;
        if (config.getBetaMax() != null && (in.beta() == null || in.beta() > config.getBetaMax().doubleValue())) return false;
        if (config.getSector() != null && !config.getSector().equals(in.sector().sector())) return false;
        if (config.getExchange() != null && !Arrays.asList(config.getExchange().split(",")).contains(in.stock().getExchange())) return false;

        RatiosTtmResDto ratios = new RatiosTtmResDto();
        ratios.setPeRatioTTM(m.per());
        ratios.setPriceToBookRatioTTM(m.pbr());
        ratios.setReturnOnEquityTTM(m.roe());
        ratios.setDebtEquityRatioTTM(m.debtEquity());
        return recommendedStocksProcessor.isUndervalued(ratios, config)
                && recommendedStocksProcessor.isGrowthSatisfied(m.avgRevenueGrowth(), m.avgNetIncomeGrowth(), config);
    }

    static RecommendedStockData toData(KrValuationInputBuilder.Input in) {
        KrValuationInputBuilder.ScreeningMetrics m = in.metrics();
        KrStockEntity s = in.stock();
        return new RecommendedStockData(s.getStockCode(), s.getStockName(), m.marketCap(), in.sector().sector(), s.getKsicName(),
                in.beta(), in.price(), null, null, s.getExchange(), s.getExchange(), KrEvaluationService.COUNTRY,
                m.per(), m.pbr(), m.roe(), m.debtEquity(), null, null, null, null, null, null, null,
                m.avgRevenueGrowth(), m.avgNetIncomeGrowth(), DateUtil.getToday("yyyyMMdd"), DateUtil.getToday("HHmmss"));
    }

    /** 국내 V9 계산이 request-scope 빈(RequestContext)에 중간값을 남김 → 백그라운드 스레드에 합성 요청 스코프 바인딩 */
    private static <T> T inRequestScope(Supplier<T> work) {
        SimpleRequestAttributes attributes = new SimpleRequestAttributes();
        RequestContextHolder.setRequestAttributes(attributes);
        try {
            return work.get();
        } finally {
            RequestContextHolder.resetRequestAttributes();
            attributes.requestCompleted();
        }
    }
}
