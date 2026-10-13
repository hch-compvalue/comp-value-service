package com.finance.dart.api.common.service.schedule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.dart.api.abroad.dto.fmp.incomestatgrowth.IncomeStatGrowthResDto;
import com.finance.dart.api.abroad.dto.fmp.ratiosttm.RatiosTtmArrReqDto;
import com.finance.dart.api.abroad.dto.fmp.ratiosttm.RatiosTtmResDto;
import com.finance.dart.api.abroad.dto.fmp.stockscreener.StockScreenerReqDto;
import com.finance.dart.api.abroad.dto.fmp.stockscreener.StockScreenerResDto;
import com.finance.dart.api.abroad.service.fmp.IncomeStatGrowthService;
import com.finance.dart.api.abroad.service.fmp.RatiosTtmService;
import com.finance.dart.api.abroad.service.fmp.StockScreenerService;
import com.finance.dart.api.common.entity.RecommendProfileConfigEntity;
import com.finance.dart.api.common.entity.RecommendProfileEntity;
import com.finance.dart.api.common.repository.RecommendProfileRepository;
import com.finance.dart.api.common.service.RecommendHistoryService;
import com.finance.dart.common.component.RedisComponent;
import com.finance.dart.common.component.RedisKeyGenerator;
import com.finance.dart.api.common.util.SecurityTypeUtil;
import com.finance.dart.common.util.DateUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 추천 종목 처리기
 * - Stock Screener로 후보군 조회
 * - RatiosTTM으로 저평가 종목 필터링
 * - Redis에 결과 저장 (TTL 24시간)
 */
@Slf4j
@AllArgsConstructor
@Service
public class RecommendedStocksProcessor {

    /** 테스트용 최대 처리 건수 (0이면 전체 처리) */
    public static final int MAX_PROCESS_COUNT_FOR_TEST = 0;

    /** API 동시 호출 건수 (1분당 300건 제한, TCP 동시연결개수 제한때문에 1초당 10건이 FMP 권장) */
    private static final int API_CALLS_PER_MINUTE = 10;

    /** API 호출 간격 (ms) - 1분 = 60,000ms, 3초 간격 */
    private static final long API_BATCH_INTERVAL_MS = 3000L;

    /** Redis TTL (24시간) */
    private static final long REDIS_TTL_HOURS = 24;

    private final StockScreenerService stockScreenerService;
    private final RatiosTtmService ratiosTtmService;
    private final IncomeStatGrowthService incomeStatGrowthService;
    private final RedisComponent redisComponent;
    private final ObjectMapper objectMapper;
    private final RecommendProfileRepository recommendProfileRepository;
    private final RecommendHistoryService recommendHistoryService;

    /**
     * 추천 종목 처리 실행
     */
    public void process() {
        process(MAX_PROCESS_COUNT_FOR_TEST);
    }

    /**
     * 추천 종목 처리 실행 (최대 건수 지정)
     * @param maxCount 최대 처리 건수 (0이면 전체)
     */
    public void process(int maxCount) {

        try {
            // 1. 활성화된 프로파일 조회
            List<RecommendProfileEntity> activeProfiles = recommendProfileRepository
                    .findByIsActiveOrderBySortOrder("Y");

            if (activeProfiles == null || activeProfiles.isEmpty()) {
                log.warn("[추천 종목] 활성화된 프로파일이 없습니다.");
                return;
            }

            log.info("[추천 종목] 활성화된 프로파일 수: {}개", activeProfiles.size());

            // 2. API 호출 결과 캐싱용 맵 (심볼별로 캐싱)
            Map<String, StockScreenerResDto> screenerCache = new HashMap<>();
            Map<String, List<RatiosTtmResDto>> ratiosCache = new HashMap<>();
            Map<String, List<IncomeStatGrowthResDto>> growthCache = new HashMap<>();

            // 3. 프로파일별로 독립적으로 처리
            for (int profileIdx = 0; profileIdx < activeProfiles.size(); profileIdx++) {
                RecommendProfileEntity profile = activeProfiles.get(profileIdx);
                log.info("[추천 종목] 프로파일 처리 시작 [{}/{}]: {}",
                        profileIdx + 1, activeProfiles.size(), profile.getProfileName());

                processProfile(profile, maxCount, screenerCache, ratiosCache, growthCache);

                log.info("[추천 종목] 프로파일 처리 완료 [{}/{}]: {}",
                        profileIdx + 1, activeProfiles.size(), profile.getProfileName());
            }

            log.info("[추천 종목] 전체 처리 완료");

        } catch (Exception e) {
            log.error("[추천 종목] 처리 중 오류 발생", e);
        }
    }

    /**
     * 프로파일별 처리
     */
    private void processProfile(
            RecommendProfileEntity profile,
            int maxCount,
            Map<String, StockScreenerResDto> screenerCache,
            Map<String, List<RatiosTtmResDto>> ratiosCache,
            Map<String, List<IncomeStatGrowthResDto>> growthCache) {

        try {
            RecommendProfileConfigEntity config = profile.getConfig();
            if (config == null) {
                log.warn("[추천 종목] 프로파일 '{}' 의 설정이 없습니다. 건너뜁니다.", profile.getProfileName());
                return;
            }

            // 1. Stock Screener로 후보군 조회 (캐시 활용)
            List<StockScreenerResDto> candidates = fetchCandidates(config, screenerCache);
            if (candidates == null || candidates.isEmpty()) {
                log.warn("[추천 종목] 프로파일 '{}' 후보군이 없습니다.", profile.getProfileName());
                return;
            }

            log.info("[추천 종목] 프로파일 '{}' 후보군 조회 완료: {}건", profile.getProfileName(), candidates.size());

            // 최대 처리 건수 적용
            if (maxCount > 0 && candidates.size() > maxCount) {
                candidates = candidates.subList(0, maxCount);
                log.info("[추천 종목] 프로파일 '{}' 최대 처리 건수 적용: {}건", profile.getProfileName(), maxCount);
            }

            // 2. 배치 단위로 RatiosTTM 조회 및 필터링
            Map<String, RecommendedStockData> undervaluedStocks = new LinkedHashMap<>();

            List<List<StockScreenerResDto>> batches = partitionList(candidates, API_CALLS_PER_MINUTE);
            log.info("[추천 종목] 프로파일 '{}' 배치 수: {} (배치당 {}건)",
                    profile.getProfileName(), batches.size(), API_CALLS_PER_MINUTE);

            for (int i = 0; i < batches.size(); i++) {
                List<StockScreenerResDto> batch = batches.get(i);
                log.info("[추천 종목] 프로파일 '{}' 배치 {}/{} 처리 중 ({}건)",
                        profile.getProfileName(), i + 1, batches.size(), batch.size());

                // RatiosTTM 병렬 조회 (캐시 활용)
                Map<String, RecommendedStockData> batchResult = processBatch(batch, config, ratiosCache, growthCache);
                undervaluedStocks.putAll(batchResult);

                log.info("[추천 종목] 프로파일 '{}' 배치 {}/{} 완료 - 이번 배치 발견: {}건 (누적: {}건)",
                        profile.getProfileName(), i + 1, batches.size(), batchResult.size(), undervaluedStocks.size());

                // 마지막 배치가 아니면 대기 (API 호출 제한)
                if (i < batches.size() - 1) {
                    log.info("[추천 종목] 프로파일 '{}' API 호출 제한으로 {}초 대기",
                            profile.getProfileName(), API_BATCH_INTERVAL_MS / 1000);
                    Thread.sleep(API_BATCH_INTERVAL_MS);
                }
            }

            log.info("[추천 종목] 프로파일 '{}' 필터링 완료 - 총 저평가 종목: {}건",
                    profile.getProfileName(), undervaluedStocks.size());

            // 3. 섹터 쿼터 적용 (특정 섹터 편중 방지)
            int beforeQuotaSize = undervaluedStocks.size();
            undervaluedStocks = applySectorQuota(undervaluedStocks, config.getSectorQuotaPct());
            if (undervaluedStocks.size() != beforeQuotaSize) {
                log.info("[추천 종목] 프로파일 '{}' 섹터 쿼터 적용 완료: {}건 → {}건",
                        profile.getProfileName(), beforeQuotaSize, undervaluedStocks.size());
            }

            // 4. Redis에 프로파일별로 저장 (빠른 조회용 캐시)
            saveToRedis(profile.getProfileName(), undervaluedStocks);

            // 5. DB에 일자별 스냅샷 영속화 (서버 재시작 보존 + 수익률 추적 기반)
            //    실패해도 Redis 저장 경로엔 영향 없도록 격리
            try {
                recommendHistoryService.saveSnapshot(profile.getProfileName(), undervaluedStocks);
            } catch (Exception e) {
                log.error("[추천 종목] 프로파일 '{}' DB 이력 저장 실패 (Redis는 정상 저장됨)",
                        profile.getProfileName(), e);
            }

        } catch (InterruptedException e) {
            log.error("[추천 종목] 프로파일 '{}' 처리 중단됨", profile.getProfileName(), e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("[추천 종목] 프로파일 '{}' 처리 중 오류 발생", profile.getProfileName(), e);
        }
    }

    /**
     * Stock Screener로 후보군 조회 (캐시 활용)
     */
    private List<StockScreenerResDto> fetchCandidates(
            RecommendProfileConfigEntity config,
            Map<String, StockScreenerResDto> screenerCache) {

        // Config 기반으로 요청 DTO 생성
        StockScreenerReqDto reqDto = buildStockScreenerRequest(config);

        // API 호출
        List<StockScreenerResDto> results = stockScreenerService.findStockScreener(reqDto);

        // 캐시에 저장 (다음 프로파일에서 재사용)
        if (results != null) {
            for (StockScreenerResDto dto : results) {
                screenerCache.putIfAbsent(dto.getSymbol(), dto);
            }
        }

        // 비일반주식 제외 필터링 (워런트/우선주/CVR/채권 등)
        if (results != null && isExcludeNonCommonStockEnabled(config)) {
            int beforeSize = results.size();
            results = results.stream()
                    .filter(dto -> !isNonCommonStock(dto.getSymbol(), dto.getCompanyName()))
                    .toList();
            int filtered = beforeSize - results.size();
            if (filtered > 0) {
                log.info("[추천 종목] 비일반주식 {}건 제외 (워런트/우선주/CVR/채권)", filtered);
            }
        }

        return results;
    }

    /**
     * 비일반주식 제외 필터 활성화 여부 (명시적으로 'N'일 때만 비활성화, NULL은 기본값 'Y' 취급)
     * - 컬럼 추가(2026-03-07) 이전에 생성된 프로파일은 NULL이므로 기본 활성화로 방어
     */
    static boolean isExcludeNonCommonStockEnabled(RecommendProfileConfigEntity config) {
        return !"N".equals(config.getExcludeNonCommonStock());
    }

    /**
     * 비일반주식 여부 판단 (공용 유틸에 위임)
     */
    static boolean isNonCommonStock(String symbol, String companyName) {
        return SecurityTypeUtil.isNonCommonStock(symbol, companyName);
    }

    /**
     * Config 기반으로 StockScreenerReqDto 생성
     */
    private StockScreenerReqDto buildStockScreenerRequest(RecommendProfileConfigEntity config) {
        StockScreenerReqDto reqDto = new StockScreenerReqDto();

        // 시가총액
        if (config.getMarketCapMin() != null) {
            reqDto.setMarketCapMoreThan(config.getMarketCapMin());
        }
        if (config.getMarketCapMax() != null) {
            reqDto.setMarketCapLowerThan(config.getMarketCapMax());
        }

        // 주가
        if (config.getPriceMin() != null) {
            reqDto.setPriceMoreThan(config.getPriceMin().doubleValue());
        }
        if (config.getPriceMax() != null) {
            reqDto.setPriceLowerThan(config.getPriceMax().doubleValue());
        }

        // 베타
        if (config.getBetaMin() != null) {
            reqDto.setBetaMoreThan(config.getBetaMin().doubleValue());
        }
        if (config.getBetaMax() != null) {
            reqDto.setBetaLowerThan(config.getBetaMax().doubleValue());
        }

        // 거래량
        if (config.getVolumeMin() != null) {
            reqDto.setVolumeMoreThan(config.getVolumeMin());
        }
        if (config.getVolumeMax() != null) {
            reqDto.setVolumeLowerThan(config.getVolumeMax());
        }

        // 섹터/산업/국가
        if (config.getSector() != null) reqDto.setSector(config.getSector());
        if (config.getIndustry() != null) reqDto.setIndustry(config.getIndustry());
        if (config.getCountry() != null) reqDto.setCountry(config.getCountry());

        // ETF/펀드 포함 여부 (Y/N -> boolean)
        reqDto.setIsEtf("Y".equals(config.getIsEtf()));
        reqDto.setIsFund("Y".equals(config.getIsFund()));
        reqDto.setIsActivelyTrading("Y".equals(config.getIsActivelyTrading()));

        // 거래소
        if (config.getExchange() != null) {
            reqDto.setExchange(config.getExchange());
        }

        // 조회 제한
        if (config.getScreenerLimit() != null) {
            reqDto.setLimit(config.getScreenerLimit());
        }

        return reqDto;
    }

    /**
     * 배치 단위로 RatiosTTM 조회 및 필터링 (캐시 활용)
     */
    private Map<String, RecommendedStockData> processBatch(
            List<StockScreenerResDto> batch,
            RecommendProfileConfigEntity config,
            Map<String, List<RatiosTtmResDto>> ratiosCache,
            Map<String, List<IncomeStatGrowthResDto>> growthCache) {

        Map<String, RecommendedStockData> result = new LinkedHashMap<>();

        // 심볼 리스트 추출
        List<String> symbols = batch.stream()
                .map(StockScreenerResDto::getSymbol)
                .toList();

        // 캐시에 없는 심볼만 조회
        List<String> symbolsToFetch = new ArrayList<>();
        for (String symbol : symbols) {
            if (!ratiosCache.containsKey(symbol)) {
                symbolsToFetch.add(symbol);
            }
        }

        // 캐시에 없는 것만 API 호출
        if (!symbolsToFetch.isEmpty()) {
            RatiosTtmArrReqDto arrReqDto = new RatiosTtmArrReqDto();
            arrReqDto.setSymbol(symbolsToFetch);

            Map<String, List<RatiosTtmResDto>> newRatiosMap = ratiosTtmService.findRatiosTTM(arrReqDto);

            // 캐시에 저장
            if (newRatiosMap != null) {
                ratiosCache.putAll(newRatiosMap);
            }
        }

        // 성장률 필터 조건 존재 여부 확인
        boolean needsGrowthData = config.getRevenueGrowthMin() != null
                || config.getRevenueGrowthMax() != null
                || config.getNetIncomeGrowthMin() != null
                || config.getNetIncomeGrowthMax() != null;

        // 성장률 데이터 조회 (필요한 경우만)
        if (needsGrowthData) {
            if (log.isDebugEnabled()) log.debug("성장률 데이터 조회 필터");
            List<String> growthSymbolsToFetch = new ArrayList<>();
            for (String symbol : symbols) {
                if (!growthCache.containsKey(symbol)) {
                    growthSymbolsToFetch.add(symbol);
                }
            }
            if (!growthSymbolsToFetch.isEmpty()) {
                Map<String, List<IncomeStatGrowthResDto>> newGrowthMap =
                        incomeStatGrowthService.findIncomeStatGrowthParallel(growthSymbolsToFetch);
                if (newGrowthMap != null) {
                    growthCache.putAll(newGrowthMap);
                }
            }
        }

        // StockScreener 결과를 Map으로 변환 (빠른 조회용)
        Map<String, StockScreenerResDto> screenerMap = new HashMap<>();
        for (StockScreenerResDto dto : batch) {
            screenerMap.put(dto.getSymbol(), dto);
        }

        // 필터링 및 결과 생성 (캐시에서 조회)
        for (String symbol : symbols) {
            List<RatiosTtmResDto> ratiosList = ratiosCache.get(symbol);

            if (ratiosList == null || ratiosList.isEmpty()) {
                continue;
            }

            RatiosTtmResDto ratios = ratiosList.get(0);

            // 3년 평균 성장률 계산
            Double avgRevenueGrowth = null;
            Double avgNetIncomeGrowth = null;
            if (needsGrowthData) {
                List<IncomeStatGrowthResDto> growthList = growthCache.get(symbol);
                avgRevenueGrowth = calcAvgGrowth(growthList, IncomeStatGrowthResDto::getGrowthRevenue);
                avgNetIncomeGrowth = calcAvgGrowth(growthList, IncomeStatGrowthResDto::getGrowthNetIncome);
            }

            // Config 기반 저평가 필터링 + 성장률 필터링
            if (isUndervalued(ratios, config) && isGrowthSatisfied(avgRevenueGrowth, avgNetIncomeGrowth, config)) {
                StockScreenerResDto screenerData = screenerMap.get(symbol);
                RecommendedStockData stockData = new RecommendedStockData(screenerData, ratios, avgRevenueGrowth, avgNetIncomeGrowth);
                result.put(symbol, stockData);
            }
        }

        return result;
    }

    /**
     * 저평가 종목 판단 (Config 기반)
     */
    public boolean isUndervalued(RatiosTtmResDto ratios, RecommendProfileConfigEntity config) {
        Double pe = ratios.getPeRatioTTM();
        Double pb = ratios.getPriceToBookRatioTTM();
        Double roe = ratios.getReturnOnEquityTTM();
        Double debtEquity = ratios.getDebtEquityRatioTTM();

        // 1. PER 기준
        boolean validPE = true;
        if (config.getPeRatioMin() != null || config.getPeRatioMax() != null) {
            if(log.isDebugEnabled()) log.debug("PER 비교, 최소 [{}] / 최대 [{}]", config.getPeRatioMin(), config.getPeRatioMax());
            if (pe == null) {
                validPE = false;
            } else {
                if (config.getPeRatioMin() != null && pe < config.getPeRatioMin().doubleValue()) {
                    validPE = false;
                }
                if (config.getPeRatioMax() != null && pe > config.getPeRatioMax().doubleValue()) {
                    validPE = false;
                }
            }
        }

        // 2. PBR 기준 (PER과 동일 패턴)
        boolean validPB = true;
        if (config.getPbRatioMin() != null || config.getPbRatioMax() != null) {
            if(log.isDebugEnabled()) log.debug("PBR 비교, 최소 [{}] / 최대 [{}]", config.getPbRatioMin(), config.getPbRatioMax());
            if (pb == null) {
                validPB = false;
            } else {
                if (config.getPbRatioMin() != null && pb < config.getPbRatioMin().doubleValue()) {
                    validPB = false;
                }
                if (config.getPbRatioMax() != null && pb > config.getPbRatioMax().doubleValue()) {
                    validPB = false;
                }
            }
        }

        // 3. ROE 기준 (동일 패턴)
        boolean validROE = true;
        if (config.getRoeMin() != null || config.getRoeMax() != null) {
            if(log.isDebugEnabled()) log.debug("ROE 비교, 최소 [{}] / 최대 [{}]", config.getRoeMin(), config.getRoeMax());
            if (roe == null) {
                validROE = false;
            } else {
                if (config.getRoeMin() != null && roe < config.getRoeMin().doubleValue()) {
                    validROE = false;
                }
                if (config.getRoeMax() != null && roe > config.getRoeMax().doubleValue()) {
                    validROE = false;
                }
            }
        }

        // 4. 부채비율 (동일 패턴)
        boolean validDebt = true;
        if (config.getDebtEquityMin() != null || config.getDebtEquityMax() != null) {
            if(log.isDebugEnabled()) log.debug("부채비율 비교, 최소 [{}] / 최대 [{}]", config.getDebtEquityMin(), config.getDebtEquityMax());
            if (debtEquity == null) {
                validDebt = false;
            } else {
                if (config.getDebtEquityMin() != null && debtEquity < config.getDebtEquityMin().doubleValue()) {
                    validDebt = false;
                }
                if (config.getDebtEquityMax() != null && debtEquity > config.getDebtEquityMax().doubleValue()) {
                    validDebt = false;
                }
            }
        }

        // 5. 적자·자본잠식 배제: 배수 상한(PER≤N, PBR≤N)은 이익·자본이 양수일 때만 '저평가' 의미가 있음
        //    (음수 PER/PBR 은 상한을 항상 통과, 순이익·자본이 모두 음수면 ROE 가 양수로 계산돼 ROE 하한도 통과)
        //    하한을 0 미만으로 명시한 경우만 음수 허용
        if (isMultipleFilterSet(config.getPeRatioMin(), config.getPeRatioMax())
                && isNonPositiveExcluded(pe, config.getPeRatioMin())) {
            validPE = false;
        }
        if (isMultipleFilterSet(config.getPbRatioMin(), config.getPbRatioMax())
                && isNonPositiveExcluded(pb, config.getPbRatioMin())) {
            validPB = false;
        }
        boolean negativeEquity = pb != null && pb < 0;
        if (negativeEquity && isMultipleFilterSet(config.getRoeMin(), config.getRoeMax())) {
            validROE = false;
        }
        if (negativeEquity && isMultipleFilterSet(config.getDebtEquityMin(), config.getDebtEquityMax())) {
            validDebt = false;
        }

        return validPE && validPB && validROE && validDebt;
    }

    private static boolean isMultipleFilterSet(BigDecimal min, BigDecimal max) {
        return min != null || max != null;
    }

    /** 값이 0 이하이고, 하한이 음수로 명시되지 않았으면 제외 대상 */
    private static boolean isNonPositiveExcluded(Double value, BigDecimal min) {
        boolean negativeAllowed = min != null && min.signum() < 0;
        return value != null && value <= 0 && !negativeAllowed;
    }

    /**
     * 성장률 필터링 조건 충족 여부 판단
     */
    public boolean isGrowthSatisfied(Double avgRevenueGrowth, Double avgNetIncomeGrowth,
                                      RecommendProfileConfigEntity config) {
        // 5. 매출 성장률 (3년 평균)
        boolean validRevGrowth = true;
        if (config.getRevenueGrowthMin() != null || config.getRevenueGrowthMax() != null) {
            if (log.isDebugEnabled()) log.debug("매출 성장률 3년평균 필터링");
            if (avgRevenueGrowth == null) {
                validRevGrowth = false;
            } else {
                if (config.getRevenueGrowthMin() != null && avgRevenueGrowth < config.getRevenueGrowthMin().doubleValue())
                    validRevGrowth = false;
                if (config.getRevenueGrowthMax() != null && avgRevenueGrowth > config.getRevenueGrowthMax().doubleValue())
                    validRevGrowth = false;
            }
        }

        // 6. 순이익 성장률 (3년 평균)
        boolean validNiGrowth = true;
        if (config.getNetIncomeGrowthMin() != null || config.getNetIncomeGrowthMax() != null) {
            if (log.isDebugEnabled()) log.debug("순이익 성장률 3년평균 필터링");
            if (avgNetIncomeGrowth == null) {
                validNiGrowth = false;
            } else {
                if (config.getNetIncomeGrowthMin() != null && avgNetIncomeGrowth < config.getNetIncomeGrowthMin().doubleValue())
                    validNiGrowth = false;
                if (config.getNetIncomeGrowthMax() != null && avgNetIncomeGrowth > config.getNetIncomeGrowthMax().doubleValue())
                    validNiGrowth = false;
            }
        }

        return validRevGrowth && validNiGrowth;
    }

    /**
     * 성장률 리스트에서 평균값 계산
     */
    private Double calcAvgGrowth(List<IncomeStatGrowthResDto> growthList,
                                 java.util.function.Function<IncomeStatGrowthResDto, Double> getter) {
        if (growthList == null || growthList.isEmpty()) return null;
        List<Double> values = growthList.stream()
                .map(getter)
                .filter(Objects::nonNull)
                .toList();
        if (values.isEmpty()) return null;
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    /**
     * 섹터 쿼터 적용
     * - 한 섹터가 결과의 quotaPct%를 초과하지 않도록, 초과 섹터는 저평가 순(PER 오름차순)으로 상한까지만 유지
     * - PER이 없거나 0 이하(적자)인 종목은 후순위
     * - quotaPct가 NULL이거나 0 이하/100 이상이면 미적용
     */
    static Map<String, RecommendedStockData> applySectorQuota(
            Map<String, RecommendedStockData> stocks, Integer quotaPct) {

        if (quotaPct == null || quotaPct <= 0 || quotaPct >= 100 || stocks.isEmpty()) {
            return stocks;
        }

        int limitPerSector = Math.max(1, (int) Math.ceil(stocks.size() * quotaPct / 100.0));

        // 섹터별 그룹핑 (섹터 없음은 미분류로 묶어 동일 규칙 적용)
        Map<String, List<RecommendedStockData>> bySector = new LinkedHashMap<>();
        for (RecommendedStockData data : stocks.values()) {
            String sector = (data.sector() == null || data.sector().isBlank()) ? "(미분류)" : data.sector();
            bySector.computeIfAbsent(sector, k -> new ArrayList<>()).add(data);
        }

        // 초과 섹터는 PER 낮은 순으로 상한까지만 유지
        Set<String> keepSymbols = new HashSet<>();
        for (Map.Entry<String, List<RecommendedStockData>> entry : bySector.entrySet()) {
            List<RecommendedStockData> sectorStocks = entry.getValue();
            if (sectorStocks.size() > limitPerSector) {
                log.info("[추천 종목] 섹터 쿼터({}%) 초과: '{}' {}건 → {}건 (PER 낮은 순 유지)",
                        quotaPct, entry.getKey(), sectorStocks.size(), limitPerSector);
                sectorStocks = sectorStocks.stream()
                        .sorted(Comparator.comparingDouble(d ->
                                (d.peRatioTTM() == null || d.peRatioTTM() <= 0) ? Double.MAX_VALUE : d.peRatioTTM()))
                        .limit(limitPerSector)
                        .toList();
            }
            for (RecommendedStockData d : sectorStocks) {
                keepSymbols.add(d.symbol());
            }
        }

        // 원래 순서를 유지하며 재구성
        Map<String, RecommendedStockData> result = new LinkedHashMap<>();
        for (Map.Entry<String, RecommendedStockData> e : stocks.entrySet()) {
            if (keepSymbols.contains(e.getKey())) {
                result.put(e.getKey(), e.getValue());
            }
        }
        return result;
    }

    /**
     * Redis에 프로파일별로 저장
     */
    private void saveToRedis(String profileName, Map<String, RecommendedStockData> undervaluedStocks) {
        try {
            // 프로파일별로 데이터를 저장
            String profileDataKey = RedisKeyGenerator.genRecommendedStocksByProfile(profileName);
            String profileDataJson = objectMapper.writeValueAsString(undervaluedStocks);
            redisComponent.saveValueWithTtl(profileDataKey, profileDataJson, REDIS_TTL_HOURS, TimeUnit.HOURS);
            log.info("[추천 종목] 프로파일 '{}' Redis 저장 완료 - Key: {}, 종목 수: {}",
                    profileName, profileDataKey, undervaluedStocks.size());

        } catch (JsonProcessingException e) {
            log.error("[추천 종목] 프로파일 '{}' Redis 저장 중 JSON 변환 오류", profileName, e);
        }
    }

    /**
     * 리스트를 지정된 크기로 분할
     */
    private <T> List<List<T>> partitionList(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }

    /**
     * 추천 종목 데이터 (StockScreener + RatiosTTM 통합)
     */
    public record RecommendedStockData(
            // StockScreener 데이터
            String symbol,
            String companyName,
            Long marketCap,
            String sector,
            String industry,
            Double beta,
            Double price,
            Double lastAnnualDividend,
            Long volume,
            String exchange,
            String exchangeShortName,
            String country,

            // RatiosTTM 주요 지표
            Double peRatioTTM,
            Double priceToBookRatioTTM,
            Double returnOnEquityTTM,
            Double debtEquityRatioTTM,
            Double dividendYieldTTM,
            Double currentRatioTTM,
            Double netProfitMarginTTM,
            Double operatingProfitMarginTTM,
            Double returnOnAssetsTTM,
            Double priceToSalesRatioTTM,
            Double enterpriseValueMultipleTTM,

            // 성장률 (3년 평균)
            Double avgRevenueGrowth,
            Double avgNetIncomeGrowth,

            // 부가 데이터
            String trscDt,
            String trscTm
    ) {
        public RecommendedStockData(StockScreenerResDto screener, RatiosTtmResDto ratios,
                                    Double avgRevenueGrowth, Double avgNetIncomeGrowth) {
            this(
                    screener.getSymbol(),
                    screener.getCompanyName(),
                    screener.getMarketCap(),
                    screener.getSector(),
                    screener.getIndustry(),
                    screener.getBeta(),
                    screener.getPrice(),
                    screener.getLastAnnualDividend(),
                    screener.getVolume(),
                    screener.getExchange(),
                    screener.getExchangeShortName(),
                    screener.getCountry(),
                    ratios.getPeRatioTTM(),
                    ratios.getPriceToBookRatioTTM(),
                    ratios.getReturnOnEquityTTM(),
                    ratios.getDebtEquityRatioTTM(),
                    ratios.getDividendYielTTM(),
                    ratios.getCurrentRatioTTM(),
                    ratios.getNetProfitMarginTTM(),
                    ratios.getOperatingProfitMarginTTM(),
                    ratios.getReturnOnAssetsTTM(),
                    ratios.getPriceToSalesRatioTTM(),
                    ratios.getEnterpriseValueMultipleTTM(),
                    avgRevenueGrowth,
                    avgNetIncomeGrowth,
                    DateUtil.getToday("yyyyMMdd"),
                    DateUtil.getToday("HHmmss")
            );
        }
    }
}
