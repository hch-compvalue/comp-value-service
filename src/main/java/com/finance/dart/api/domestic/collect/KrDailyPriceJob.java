package com.finance.dart.api.domestic.collect;

import com.finance.dart.api.common.service.schedule.*;
import com.finance.dart.api.domestic.entity.KrStockEntity;
import com.finance.dart.api.domestic.enums.KrExchange;
import com.finance.dart.api.domestic.kis.KisApiException;
import com.finance.dart.api.domestic.repository.KrPriceJdbcRepository;
import com.finance.dart.api.domestic.repository.KrPriceJdbcRepository.LastBar;
import com.finance.dart.api.domestic.repository.KrStockRepository;
import com.finance.dart.common.logging.TransactionLogging;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 국내 종목·일봉 수집 스케줄 (KR_DAILY_PRICE)
 * 종목 마스터 갱신 → 지수 일봉 → 종목 일봉(3개 스레드, KIS 초당 8건 제한은 KisRateLimiter가 전체 공유)
 * - 종목 단위로 저장하므로 중간에 끊겨도(배포·PC 꺼짐) 다음 실행이 이어서 받는다
 * - 연속 실패가 많으면(KIS 장애·토큰 문제) 나머지를 건너뛰고 중단
 */
@Slf4j
@Service
public class KrDailyPriceJob implements ManagedScheduleJob {

    static final int WORKER_THREADS = 3;
    static final int MAX_CONSECUTIVE_FAILURES = 30;
    /** 실패 종목이 이 비율 이하면 성공으로 기록 (다음 실행이 증분으로 메움) */
    static final double FAILURE_TOLERANCE_RATIO = 0.01;
    private static final long MAX_RUN_HOURS = 4;
    private static final int PROGRESS_LOG_EVERY = 250;
    private static final String ACTIVE = "Y";

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ExecutorService workers;

    private final KrStockMasterService masterService;
    private final KrDailyPriceCollector collector;
    private final KrStockRepository krStockRepository;
    private final KrPriceJdbcRepository priceRepository;
    private final ScheduleRunHistoryService scheduleRunHistoryService;

    public KrDailyPriceJob(KrStockMasterService masterService, KrDailyPriceCollector collector,
                           KrStockRepository krStockRepository, KrPriceJdbcRepository priceRepository,
                           ScheduleRunHistoryService scheduleRunHistoryService) {
        this.masterService = masterService;
        this.collector = collector;
        this.krStockRepository = krStockRepository;
        this.priceRepository = priceRepository;
        this.scheduleRunHistoryService = scheduleRunHistoryService;
    }

    @Override
    public ScheduleJobType type() {
        return ScheduleJobType.KR_DAILY_PRICE;
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /**
     * @param maxCount 일봉을 받을 종목 수 제한 (0이면 전체)
     */
    @Override
    @TransactionLogging
    public boolean start(ScheduleTriggerType trigger, int maxCount) {
        if (!running.compareAndSet(false, true)) {
            log.warn("[국내 수집] 이미 실행 중 — {} 실행 요청 거절", trigger);
            return false;
        }

        Long historyId = null;
        try {
            historyId = scheduleRunHistoryService.start(type(), trigger, maxCount);
            final Long id = historyId;
            executorService.submit(() -> runInBackground(id, maxCount));
            return true;
        } catch (RejectedExecutionException e) {
            log.error("[국내 수집] 실행 대기열 등록 실패", e);
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
            LocalDate settled = KrDailyPriceCollector.settledDate(LocalDateTime.now(ScheduleManagerService.SCHEDULE_ZONE));
            String master = refreshMaster();
            String index = collectIndexes(settled);
            StockSummary stocks = collectStocks(settled, maxCount);
            status = stocks.isSuccess() ? ScheduleRunStatus.SUCCESS : ScheduleRunStatus.FAILED;
            message = String.format("기준일 %s / %s / %s / %s", settled, master, index, stocks.summary());
        } catch (KrCollectException | DataAccessException e) {
            log.error("[국내 수집] 중단", e);
            message = "중단: " + e.getMessage();
        } catch (RuntimeException e) {
            log.error("[국내 수집] 처리 중 예기치 못한 오류", e);
            message = "처리 중 오류: " + e.getMessage();
        } finally {
            log.info("[국내 수집] 종료 {} — {}", status, message);
            finishHistory(historyId, status, message);
            running.set(false);
        }
    }

    /** 마스터 갱신 실패 시 기존 종목 목록이 있으면 그대로 진행 */
    private String refreshMaster() {
        try {
            return masterService.refresh().summary();
        } catch (KrCollectException | DataAccessException e) {
            if (krStockRepository.findByActiveOrderByStockCode(ACTIVE).isEmpty()) throw e;
            log.warn("[국내 수집] 종목 마스터 갱신 실패 — 기존 목록으로 진행: {}", e.getMessage());
            return "마스터 갱신 실패(기존 목록 사용)";
        }
    }

    private String collectIndexes(LocalDate settled) {
        Map<KrExchange, String> results = new EnumMap<>(KrExchange.class);
        for (KrExchange exchange : KrExchange.values()) {
            try {
                results.put(exchange, collector.collectIndex(exchange.getIndexCode(), settled) + "행");
            } catch (KisApiException | DataAccessException e) {
                log.warn("[국내 수집] {} 지수 실패: {}", exchange, e.getMessage());
                results.put(exchange, "실패");
            }
        }
        return "지수 " + results;
    }

    private StockSummary collectStocks(LocalDate settled, int maxCount) {
        List<KrStockEntity> stocks = krStockRepository.findByActiveOrderByStockCode(ACTIVE);
        if (maxCount > 0 && stocks.size() > maxCount) stocks = stocks.subList(0, maxCount);
        Map<String, LastBar> lastBars = priceRepository.findLastDailyBars();

        StockSummary summary = new StockSummary(stocks.size());
        ExecutorService pool = Executors.newFixedThreadPool(WORKER_THREADS);
        workers = pool;
        try {
            for (KrStockEntity stock : stocks) {
                pool.submit(() -> collectOne(stock.getStockCode(), lastBars.get(stock.getStockCode()), settled, summary));
            }
            pool.shutdown();
            if (!pool.awaitTermination(MAX_RUN_HOURS, TimeUnit.HOURS)) {
                pool.shutdownNow();
                summary.aborted.set(true);
                log.error("[국내 수집] {}시간 초과 — 중단", MAX_RUN_HOURS);
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            summary.aborted.set(true);
            Thread.currentThread().interrupt();
        } finally {
            workers = null;
        }
        return summary;
    }

    private void collectOne(String stockCode, LastBar last, LocalDate settled, StockSummary summary) {
        if (summary.aborted.get()) {
            summary.skipped.incrementAndGet();
            return;
        }
        try {
            KrDailyPriceCollector.Outcome outcome = collector.collectStock(stockCode, last, settled);
            summary.record(outcome);
        } catch (KisApiException | KrCollectException | DataAccessException e) {
            log.warn("[국내 수집] {} 일봉 실패: {}", stockCode, e.getMessage());
            summary.recordFailure();
        }
        int done = summary.done.incrementAndGet();
        if (done % PROGRESS_LOG_EVERY == 0) log.info("[국내 수집] 일봉 진행 {}/{} — {}", done, summary.total, summary.summary());
    }

    private void finishHistory(Long historyId, ScheduleRunStatus status, String message) {
        try {
            scheduleRunHistoryService.finish(historyId, status, message);
        } catch (RuntimeException e) {
            log.error("[국내 수집] 실행 이력 종료 기록 실패 id={}", historyId, e);
        }
    }

    @PreDestroy
    public void shutdownExecutor() {
        ExecutorService current = workers;
        if (current != null) current.shutdownNow();     // 진행 중 종목은 저장 전이면 버려지고 다음 실행이 다시 받음
        executorService.shutdownNow();
    }

    /** 종목 일봉 수집 집계 (스레드 공유) */
    static final class StockSummary {
        final int total;
        final AtomicInteger done = new AtomicInteger();
        final AtomicInteger full = new AtomicInteger();
        final AtomicInteger incremental = new AtomicInteger();
        final AtomicInteger readjusted = new AtomicInteger();
        final AtomicInteger upToDate = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final AtomicInteger skipped = new AtomicInteger();
        final AtomicInteger consecutiveFailures = new AtomicInteger();
        final AtomicBoolean aborted = new AtomicBoolean(false);

        StockSummary(int total) {
            this.total = total;
        }

        void record(KrDailyPriceCollector.Outcome outcome) {
            consecutiveFailures.set(0);
            switch (outcome) {
                case FULL -> full.incrementAndGet();
                case INCREMENTAL -> incremental.incrementAndGet();
                case READJUSTED -> readjusted.incrementAndGet();
                case UP_TO_DATE -> upToDate.incrementAndGet();
            }
        }

        void recordFailure() {
            failed.incrementAndGet();
            if (consecutiveFailures.incrementAndGet() >= MAX_CONSECUTIVE_FAILURES && aborted.compareAndSet(false, true)) {
                log.error("[국내 수집] 연속 {}건 실패 — 나머지 종목 건너뜀", MAX_CONSECUTIVE_FAILURES);
            }
        }

        boolean isSuccess() {
            return !aborted.get() && failed.get() <= total * FAILURE_TOLERANCE_RATIO;
        }

        String summary() {
            return String.format("일봉 %d종목: 전체 %d, 증분 %d, 수정주가 재수집 %d, 최신 %d, 실패 %d%s",
                    total, full.get(), incremental.get(), readjusted.get(), upToDate.get(), failed.get(),
                    aborted.get() ? ", 중단(건너뜀 " + skipped.get() + ")" : "");
        }
    }
}
