package com.finance.dart.api.common.service.schedule;


import com.finance.dart.common.logging.TransactionLogging;
import jakarta.annotation.PreDestroy;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 추천 기업 조회 스케줄러
 * RecommendedStocks(스케줄러) >> RecommendedStocksProcessor(처리)
 * - 실행 주기는 ScheduleManagerService가 DB 설정으로 등록 (기본 매일 10시, 관리 > 스케줄 관리에서 변경)
 */
@Slf4j
@AllArgsConstructor
@Service
public class RecommendedStocks implements ManagedScheduleJob {

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    /** 실행 중 여부 (중복 실행 시 FMP 호출 한도 초과 → 자동·수동 모두 1건만 허용) */
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final RecommendedStocksProcessor recommendedStocksProcessor;
    private final RecommendedStocksEvaluator recommendedStocksEvaluator;
    private final ScheduleRunHistoryService scheduleRunHistoryService;

    @Override
    public ScheduleJobType type() {
        return ScheduleJobType.RECOMMENDED_STOCKS;
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /**
     * 추천 종목 처리 실행 (백그라운드)
     * @param trigger 실행 계기
     * @param maxCount 프로파일별 최대 처리 건수 (0이면 전체)
     */
    @Override
    @TransactionLogging
    public boolean start(ScheduleTriggerType trigger, int maxCount) {
        if (!running.compareAndSet(false, true)) {
            log.warn("[추천 종목] 이미 실행 중 — {} 실행 요청 거절", trigger);
            return false;
        }

        Long historyId = null;
        try {
            historyId = scheduleRunHistoryService.start(type(), trigger, maxCount);
            final Long id = historyId;
            executorService.submit(() -> processDataInBackground(id, maxCount));
            return true;
        } catch (RejectedExecutionException e) {
            log.error("[추천 종목] 실행 대기열 등록 실패", e);
            if (historyId != null) scheduleRunHistoryService.finish(historyId, ScheduleRunStatus.FAILED, "실행 대기열 등록 실패");
            running.set(false);
            return false;
        } catch (RuntimeException e) {
            running.set(false);     // 이력 저장 실패 등 — 플래그가 남으면 이후 실행이 영구히 막힘
            throw e;
        }
    }

    private void processDataInBackground(Long historyId, int maxCount) {
        ScheduleRunStatus status = ScheduleRunStatus.FAILED;
        String message = "알 수 없는 오류 (로그 확인)";
        try {
            log.info("[추천 종목] 처리 시작 (maxCount={})", maxCount);
            boolean processed = recommendedStocksProcessor.process(maxCount);
            int saved = recommendedStocksEvaluator.evaluateAll();  // 추천 완료 후 전수 평가 (2-2)

            boolean evaluated = saved != RecommendedStocksEvaluator.EVALUATION_FAILED;
            status = (processed && evaluated) ? ScheduleRunStatus.SUCCESS : ScheduleRunStatus.FAILED;
            message = String.format("추천 수집 %s / 전수 평가 %s",
                    processed ? "완료" : "오류(로그 확인)",
                    evaluated ? "저장 " + saved + "건" : "오류(로그 확인)");
        } catch (RuntimeException e) {
            log.error("[추천 종목] 처리 중 예기치 못한 오류", e);
            message = "처리 중 오류: " + e.getMessage();
        } finally {
            finishHistory(historyId, status, message);
            running.set(false);
        }
    }

    private void finishHistory(Long historyId, ScheduleRunStatus status, String message) {
        try {
            scheduleRunHistoryService.finish(historyId, status, message);
        } catch (RuntimeException e) {
            log.error("[추천 종목] 실행 이력 종료 기록 실패 id={}", historyId, e);
        }
    }

    @PreDestroy
    public void shutdownExecutor() {
        executorService.shutdown();
        if(log.isDebugEnabled()) log.info("[추천 종목] ExecutorService 종료");
        try {
            if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                log.error("[추천 종목] ExecutorService 강제 종료");
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.error("[추천 종목] ExecutorService 종료 오류");
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

}
