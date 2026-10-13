package com.finance.dart.api.common.controller;

import com.finance.dart.api.abroad.service.US_StockCalFromFpmService;
import com.finance.dart.api.common.dto.CompanySharePriceCalculator;
import com.finance.dart.api.common.dto.CompanySharePriceResult;
import com.finance.dart.api.common.dto.evaluation.StockEvaluationRequest;
import com.finance.dart.api.common.dto.evaluation.StockEvaluationResponse;
import com.finance.dart.api.common.entity.EvaluationHistoryEntity;
import com.finance.dart.api.common.service.EvaluationHistoryService;
import com.finance.dart.api.common.service.EvaluationPerformanceService;
import com.finance.dart.api.common.service.PerShareValueCalculationService;
import com.finance.dart.api.common.service.StockEvaluationService;
import com.finance.dart.api.domestic.service.DomesticStockCalculationService;
import com.finance.dart.common.config.EndPointConfig;
import com.finance.dart.common.dto.CommonResponse;
import com.finance.dart.common.logging.TransactionLogging;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.finance.dart.common.component.RedisComponent;
import com.finance.dart.common.component.RedisKeyGenerator;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@AllArgsConstructor
@RequestMapping("main")
@RestController
public class MainController {

    private final DomesticStockCalculationService domesticStockCalculationService;  // 국내주식계산 서비스
    private final US_StockCalFromFpmService US_StockCalFromFmpService;              // 미국주식계산 서비스(FMP)
    private final PerShareValueCalculationService perShareValueCalculationService;  // 가치계산 서비스
    private final StockEvaluationService stockEvaluationService;                    // 종목평가 서비스
    private final EvaluationHistoryService evaluationHistoryService;                // 평가 이력 서비스 (2-2)
    private final EvaluationPerformanceService evaluationPerformanceService;        // 수익률 추적 서비스 (2-4)
    private final RedisComponent redisComponent;

    /** 투자판정 정렬 우선순위 (높을수록 상위) */
    private static final Map<String, Integer> INVEST_SIGNAL_RANK = Map.of(
            "매수 후보", 3, "관심목록", 2, "관망", 1);


    /**
     * 헬스체크
     * @return
     */
    @TransactionLogging
    @EndPointConfig.PublicEndpoint
    @GetMapping("/check")
    public ResponseEntity<Object> healthCheck() {
        log.info("HealthCheck!!!_헬스체크!!!");
        return new ResponseEntity<>("Check OK", HttpStatus.OK);
    }

    /**
     * <pre>
     * 국내기업
     * 한 기업의 한주당 가치 계산
     * </pre>
     * @param year
     * @param corpCode
     * @param corpName
     * @return
     */
    @TransactionLogging
    @GetMapping("/cal/per_value")
    public ResponseEntity<CompanySharePriceResult> calDomesticCompanyStockPerValue(
            @RequestParam("year") String year,
            @RequestParam(value = "corp_code", defaultValue = "") String corpCode,
            @RequestParam(value = "corp_name", defaultValue = "") String corpName
    ) {

        if(log.isDebugEnabled()) log.debug("year={}, corp_code={}, corp_name={}", year, corpCode, corpName);

        CompanySharePriceResult response = null;
        try {
            response = domesticStockCalculationService.calPerValue(year, corpCode, corpName);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        if(log.isDebugEnabled()) log.debug("response = {}", response);

        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /**
     * 국내 기업의 한주당 가치 수동 계산
     * @param companySharePriceCalculator
     * @return
     */
    @TransactionLogging
    @PostMapping("/cal/per_value/manual")
    public ResponseEntity<Map> calCompanyStockPerValueManual(
            @RequestBody CompanySharePriceCalculator companySharePriceCalculator
    ) {

        String response = perShareValueCalculationService.calPerValue(companySharePriceCalculator);
        if(log.isDebugEnabled()) log.debug("response = {}", response);

        Map<String, Object> responseMap = new LinkedHashMap<>();
        responseMap.put("result", response);

        return new ResponseEntity<>(responseMap, HttpStatus.OK);
    }

    /**
     * <pre>
     * 해외기업
     * 한 기업의 한주당 가치 계산 (최신)
     * </pre>
     * @param symbol
     * @return
     * @throws Exception
     */
    @TransactionLogging
    @GetMapping("/cal/per_value/abroad")
    public ResponseEntity<Object> calAbroadCompanyStockPerValue(@RequestParam("symbol") String symbol)
            throws Exception {

        // 버전 변경시 EvaluationConst.java 상수 수정 필요
        CompanySharePriceResult responseBody = US_StockCalFromFmpService.calPerValue(symbol);   // FMP

        return new ResponseEntity<>(new CommonResponse<>(responseBody), HttpStatus.OK);
    }

    /**
     * <pre>
     * 해외기업 종목 평가 (다건)
     * Step 1~4 상세 평가를 통한 100점 만점 점수 산출
     * - Step 1 (20점): 위험 신호 확인 (치명적 결함 필터)
     * - Step 2 (25점): 신뢰도 확인 (재무 건전성)
     * - Step 3 (40점): 밸류에이션 평가 (PEG, 가격차이, 성장률) ⭐ 가장 중요
     * - Step 4 (15점): 영업이익 추세 확인 (성장 지속가능성)
     *
     * 등급: S(90+), A(80+), B(70+), C(60+), D(50+), F(50미만)
     * </pre>
     * @param request 평가 요청 (심볼 리스트)
     * @return 평가 결과 리스트
     */
    @TransactionLogging
    @PostMapping("/evaluate/stocks")
    public ResponseEntity<CommonResponse<List<StockEvaluationResponse>>> evaluateStocks(
            @RequestBody StockEvaluationRequest request) {

        List<StockEvaluationResponse> responseBody = stockEvaluationService.evaluateStocks(request);

        return new ResponseEntity<>(new CommonResponse<>(responseBody), HttpStatus.OK);
    }

    /**
     * 일별 자동 전수 평가 결과 조회 (2-2) - 매수후보 목록
     * @param date 조회 일자 (yyyy-MM-dd, 미지정 시 최신 일자)
     * @return 투자판정(매수후보>관심목록>관망) → 가치점수 내림차순 정렬 목록
     */
    @TransactionLogging
    @GetMapping("/evaluation/daily")
    public ResponseEntity<CommonResponse<List<EvaluationHistoryEntity>>> getDailyEvaluation(
            @RequestParam(value = "date", required = false) String date) {

        java.time.LocalDate targetDate = null;
        if (date != null && !date.isEmpty()) {
            targetDate = java.time.LocalDate.parse(date);
        }

        List<EvaluationHistoryEntity> list = evaluationHistoryService.getSnapshot(targetDate);

        // 투자판정 우선순위 → 가치점수 내림차순 정렬
        list.sort(Comparator
                .comparingInt((EvaluationHistoryEntity e) ->
                        INVEST_SIGNAL_RANK.getOrDefault(e.getInvestmentSignal(), 0)).reversed()
                .thenComparing(e -> e.getValueScore() == null ? 0.0 : e.getValueScore(),
                        Comparator.reverseOrder()));

        return new ResponseEntity<>(new CommonResponse<>(list), HttpStatus.OK);
    }

    /**
     * 수익률 추적 (2-4) - 기준일의 투자판정/가치등급별 이후 실제 수익률 집계
     * @param from 기준일 (yyyy-MM-dd, 필수)
     * @return 투자판정별·가치등급별 평균수익률/승률 집계
     */
    @TransactionLogging
    @GetMapping("/evaluation/performance")
    public ResponseEntity<CommonResponse<Map<String, Object>>> getEvaluationPerformance(
            @RequestParam("from") String from) {

        java.time.LocalDate fromDate = java.time.LocalDate.parse(from);
        Map<String, Object> result = evaluationPerformanceService.computePerformance(fromDate);

        return new ResponseEntity<>(new CommonResponse<>(result), HttpStatus.OK);
    }

    private static final int MAX_RECENT_QUERIES = 3;
    private static final Gson gson = new Gson();

    /**
     * 투자판단 최근 조회 내역 저장 (프론트에서 분석 버튼 클릭 시 전체 심볼 리스트로 1회 호출)
     */
    @PostMapping("/evaluate/recent-queries")
    public ResponseEntity<CommonResponse<Void>> saveRecentQueryApi(
            @RequestBody StockEvaluationRequest request) {
        try {
            saveRecentQuery(request.getSymbols());
        } catch (Exception e) {
            log.warn("최근 조회 내역 저장 실패", e);
        }
        return new ResponseEntity<>(new CommonResponse<>(null), HttpStatus.OK);
    }

    /**
     * 투자판단 최근 조회 내역 조회
     */
    @GetMapping("/evaluate/recent-queries")
    public ResponseEntity<CommonResponse<List<Map<String, Object>>>> getRecentQueries() {
        String redisKey = RedisKeyGenerator.genEvaluationRecentQueries();
        String cached = redisComponent.getValue(redisKey);
        List<Map<String, Object>> queries = cached != null
                ? gson.fromJson(cached, new TypeToken<List<Map<String, Object>>>(){}.getType())
                : List.of();
        return new ResponseEntity<>(new CommonResponse<>(queries), HttpStatus.OK);
    }

    /**
     * 최근 조회 내역 저장 (최대 3건, 중복 제거)
     */
    private void saveRecentQuery(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) return;

        String redisKey = RedisKeyGenerator.genEvaluationRecentQueries();
        String cached = redisComponent.getValue(redisKey);
        List<Map<String, Object>> queries = cached != null
                ? gson.fromJson(cached, new TypeToken<List<Map<String, Object>>>(){}.getType())
                : new ArrayList<>();

        // 동일 심볼 세트 중복 제거 (정렬 후 비교)
        List<String> sorted = symbols.stream().sorted().toList();
        queries.removeIf(q -> {
            List<String> existing = ((List<?>) q.get("symbols")).stream()
                    .map(Object::toString).sorted().toList();
            return existing.equals(sorted);
        });

        // 새 항목 추가 (맨 앞)
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("symbols", symbols);
        entry.put("symbolCount", symbols.size());
        entry.put("queriedAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        queries.add(0, entry);

        // 최대 3건 유지
        if (queries.size() > MAX_RECENT_QUERIES) {
            queries = new ArrayList<>(queries.subList(0, MAX_RECENT_QUERIES));
        }

        redisComponent.saveValueWithTtl(redisKey, gson.toJson(queries), 604800); // 7일 TTL
    }

}