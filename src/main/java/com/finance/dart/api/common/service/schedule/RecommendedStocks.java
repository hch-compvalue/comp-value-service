package com.finance.dart.api.common.service.schedule;


import com.finance.dart.common.logging.TransactionLogging;
import jakarta.annotation.PreDestroy;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 추천 기업 조회 스케줄러
 * RecommendedStocks(스케줄러) >> RecommendedStocksProcessor(처리)
 */
@Slf4j
@AllArgsConstructor
@Service
public class RecommendedStocks {

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    private final RecommendedStocksProcessor recommendedStocksProcessor;
    private final RecommendedStocksEvaluator recommendedStocksEvaluator;

    @TransactionLogging
    @Scheduled(cron = "0 0 10 * * *") // 24시간 형식, 초 분 시 일 월 요일, 10시(오전) 시작 - 미국장 종가 확정 후 실행
    public void startScheduledTask() {
        executorService.submit(this::processDataInBackground);
    }

    /**
     * 추천 종목 처리 실행 (테스트용 - 최대 건수 지정)
     * @param maxCount 최대 처리 건수 (0이면 전체)
     */
    public void startScheduledTask(int maxCount) {
        executorService.submit(() -> {
            recommendedStocksProcessor.process(maxCount);
            recommendedStocksEvaluator.evaluateAll();  // 추천 완료 후 전수 평가 (2-2)
        });
    }

    private void processDataInBackground() {
        log.info("[추천 종목] 처리 시작");
        recommendedStocksProcessor.process();
        recommendedStocksEvaluator.evaluateAll();  // 추천 완료 후 전수 평가 (2-2)
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
