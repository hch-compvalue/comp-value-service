package com.finance.dart.api.domestic.valuation;

import com.finance.dart.api.common.service.schedule.*;
import com.finance.dart.common.logging.TransactionLogging;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 국내 추천 종목 수집·전수 평가 스케줄 (KR_RECOMMEND) — 처리는 KrRecommendService
 */
@Slf4j
@Service
public class KrRecommendJob implements ManagedScheduleJob {

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final KrRecommendService recommendService;
    private final ScheduleRunHistoryService scheduleRunHistoryService;

    public KrRecommendJob(KrRecommendService recommendService, ScheduleRunHistoryService scheduleRunHistoryService) {
        this.recommendService = recommendService;
        this.scheduleRunHistoryService = scheduleRunHistoryService;
    }

    @Override
    public ScheduleJobType type() {
        return ScheduleJobType.KR_RECOMMEND;
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /**
     * @param maxCount 계산할 종목 수 제한 (0이면 전체)
     */
    @Override
    @TransactionLogging
    public boolean start(ScheduleTriggerType trigger, int maxCount) {
        if (!running.compareAndSet(false, true)) {
            log.warn("[국내 추천] 이미 실행 중 — {} 실행 요청 거절", trigger);
            return false;
        }
        Long historyId = null;
        try {
            historyId = scheduleRunHistoryService.start(type(), trigger, maxCount);
            final Long id = historyId;
            executorService.submit(() -> runInBackground(id, maxCount));
            return true;
        } catch (RejectedExecutionException e) {
            log.error("[국내 추천] 실행 대기열 등록 실패", e);
            if (historyId != null) scheduleRunHistoryService.finish(historyId, ScheduleRunStatus.FAILED, "실행 대기열 등록 실패");
            running.set(false);
            return false;
        } catch (RuntimeException e) {
            running.set(false);     // 이력 저장 실패 등 — 플래그가 남으면 이후 실행이 영구히 막힘
            throw e;
        }
    }

    private void runInBackground(Long historyId, int maxCount) {
        ScheduleRunStatus status = ScheduleRunStatus.FAILED;
        String message = "알 수 없는 오류 (로그 확인)";
        try {
            KrRecommendService.Summary summary = recommendService.run(maxCount);
            status = ScheduleRunStatus.SUCCESS;
            message = summary.text();
        } catch (DataAccessException e) {
            log.error("[국내 추천] 중단", e);
            message = "중단: " + e.getMessage();
        } catch (RuntimeException e) {
            log.error("[국내 추천] 처리 중 예기치 못한 오류", e);
            message = "처리 중 오류: " + e.getMessage();
        } finally {
            log.info("[국내 추천] 종료 {} — {}", status, message);
            try {
                scheduleRunHistoryService.finish(historyId, status, message);
            } catch (RuntimeException e) {
                log.error("[국내 추천] 실행 이력 종료 기록 실패 id={}", historyId, e);
            }
            running.set(false);
        }
    }

    @PreDestroy
    public void shutdownExecutor() {
        executorService.shutdownNow();
    }
}
