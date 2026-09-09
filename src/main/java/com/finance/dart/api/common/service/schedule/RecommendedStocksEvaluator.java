package com.finance.dart.api.common.service.schedule;

import com.finance.dart.api.common.context.SimpleRequestAttributes;
import com.finance.dart.api.common.dto.evaluation.StockEvaluationRequest;
import com.finance.dart.api.common.dto.evaluation.StockEvaluationResponse;
import com.finance.dart.api.common.entity.RecommendProfileEntity;
import com.finance.dart.api.common.repository.RecommendProfileRepository;
import com.finance.dart.api.common.service.EvaluationHistoryService;
import com.finance.dart.api.common.service.RecommendedCompanyService;
import com.finance.dart.api.common.service.StockEvaluationService;
import com.finance.dart.api.common.service.schedule.RecommendedStocksProcessor.RecommendedStockData;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 추천 종목 일별 자동 전수 평가기 (2-2)
 * - 추천 스케줄(매일 10시) 완료 후 실행: 활성 프로파일들의 추천 종목을 중복제거하여 전수 V8 평가
 * - 평가 결과를 일자별 이력으로 영속화 → 매수후보 목록 + 수익률 추적(2-4) 기반
 */
@Slf4j
@Service
@AllArgsConstructor
public class RecommendedStocksEvaluator {

    /** 평가 배치 크기 (StockEvaluationService.MAX_EVALUATION_SYMBOLS 이하) */
    private static final int EVAL_BATCH_SIZE = 50;

    private final RecommendProfileRepository recommendProfileRepository;
    private final RecommendedCompanyService recommendedCompanyService;
    private final StockEvaluationService stockEvaluationService;
    private final EvaluationHistoryService evaluationHistoryService;

    /**
     * 추천된 전 종목 전수 평가 실행
     */
    public void evaluateAll() {
        try {
            // 1. 활성 프로파일들의 추천 종목에서 고유 심볼 수집 (프로파일 간 중복 제거)
            Set<String> uniqueSymbols = collectUniqueSymbols();
            if (uniqueSymbols.isEmpty()) {
                log.warn("[전수 평가] 평가할 추천 종목이 없습니다. 건너뜁니다.");
                return;
            }

            log.info("[전수 평가] 시작 - 고유 종목 수: {} (배치당 {}건)", uniqueSymbols.size(), EVAL_BATCH_SIZE);

            // 2. 배치 단위로 평가 (StockEvaluationService가 종목 간 FMP rate limit 대기)
            List<String> symbolList = new ArrayList<>(uniqueSymbols);
            List<StockEvaluationResponse> allResponses = new ArrayList<>();

            List<List<String>> batches = partition(symbolList, EVAL_BATCH_SIZE);
            for (int i = 0; i < batches.size(); i++) {
                List<String> batch = batches.get(i);
                StockEvaluationRequest request = new StockEvaluationRequest();
                request.setSymbols(batch);

                // 평가 경로가 request-scope 빈(RequestContext)에 의존 → 백그라운드 스레드에 합성 요청 스코프 바인딩
                // (정상 HTTP 요청 1건 = 배치 1건과 동일 구조)
                List<StockEvaluationResponse> responses = evaluateInRequestScope(request);
                allResponses.addAll(responses);

                long ok = responses.stream().filter(r -> !"ERROR".equals(r.getGrade())).count();
                log.info("[전수 평가] 배치 {}/{} 완료 - {}건 중 성공 {}건 (누적 {}건)",
                        i + 1, batches.size(), batch.size(), ok, allResponses.size());
            }

            // 3. 평가 이력 영속화 (ERROR 제외는 서비스에서 처리)
            int saved = evaluationHistoryService.saveSnapshot(allResponses);
            log.info("[전수 평가] 완료 - 평가 {}건, 저장 {}건", allResponses.size(), saved);

        } catch (Exception e) {
            log.error("[전수 평가] 처리 중 오류 발생", e);
        }
    }

    /**
     * 합성 요청 스코프를 바인딩한 채 평가 실행 (백그라운드 스레드에서 request-scope 빈 사용)
     */
    private List<StockEvaluationResponse> evaluateInRequestScope(StockEvaluationRequest request) {
        SimpleRequestAttributes attributes = new SimpleRequestAttributes();
        RequestContextHolder.setRequestAttributes(attributes);
        try {
            return stockEvaluationService.evaluateStocks(request);
        } finally {
            RequestContextHolder.resetRequestAttributes();
            attributes.requestCompleted();  // request-scope 빈 소멸 콜백 실행
        }
    }

    /**
     * 활성 프로파일들의 추천 종목에서 고유 심볼 수집
     */
    private Set<String> collectUniqueSymbols() {
        Set<String> symbols = new LinkedHashSet<>();
        List<RecommendProfileEntity> activeProfiles =
                recommendProfileRepository.findByIsActiveOrderBySortOrder("Y");
        if (activeProfiles == null) return symbols;

        for (RecommendProfileEntity profile : activeProfiles) {
            List<RecommendedStockData> stocks =
                    recommendedCompanyService.getAbroadCompanyByProfile(profile.getProfileName());
            for (RecommendedStockData s : stocks) {
                if (s.symbol() != null && !s.symbol().isEmpty()) {
                    symbols.add(s.symbol());
                }
            }
        }
        return symbols;
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }
}
