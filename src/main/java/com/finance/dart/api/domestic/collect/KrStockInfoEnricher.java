package com.finance.dart.api.domestic.collect;

import com.finance.dart.api.domestic.entity.KrStockEntity;
import com.finance.dart.api.domestic.kis.KisApi;
import com.finance.dart.api.domestic.kis.KisApiException;
import com.finance.dart.api.domestic.kis.KisClient;
import com.finance.dart.api.domestic.repository.KrStockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 종목 업종(표준산업분류 KSIC) 보강 — KIS 주식기본조회
 * - KIS 지수업종은 코스닥 약 250종목이 비어 있고(0000) 금융이 은행·지주로 나뉘지 않아 3단계 업종 매핑에 부족 → KSIC 사용
 * - 업종이 없거나 30일 지난 종목만 (첫 실행 약 2,500건, 이후 신규 상장·월 1회 갱신분)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KrStockInfoEnricher {

    static final int REFRESH_DAYS = 30;
    static final int WORKER_THREADS = 3;
    static final int MAX_CONSECUTIVE_FAILURES = 30;
    private static final long MAX_RUN_MINUTES = 60;
    private static final String ACTIVE = "Y";
    private static final String PRODUCT_TYPE_STOCK = "300";

    private final KisClient kisClient;
    private final KrStockRepository krStockRepository;

    /** 보강 결과 */
    public record Result(int target, int updated, int failed) {
        public String summary() {
            return String.format("업종 보강 %d종목 (갱신 %d, 실패 %d)", target, updated, failed);
        }
    }

    public Result enrich(LocalDateTime now) {
        List<KrStockEntity> targets = krStockRepository.findKsicTargets(ACTIVE, now.minusDays(REFRESH_DAYS));
        AtomicInteger updated = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger consecutiveFailures = new AtomicInteger();
        AtomicBoolean aborted = new AtomicBoolean(false);

        ExecutorService pool = Executors.newFixedThreadPool(WORKER_THREADS);
        try {
            for (KrStockEntity stock : targets) {
                pool.submit(() -> {
                    if (aborted.get()) return;
                    try {
                        enrichOne(stock, now);
                        updated.incrementAndGet();
                        consecutiveFailures.set(0);
                    } catch (KisApiException | DataAccessException e) {
                        failed.incrementAndGet();
                        log.warn("[국내 수집] {} 업종 조회 실패: {}", stock.getStockCode(), e.getMessage());
                        if (consecutiveFailures.incrementAndGet() >= MAX_CONSECUTIVE_FAILURES && aborted.compareAndSet(false, true)) {
                            log.error("[국내 수집] 업종 조회 연속 {}건 실패 — 나머지는 다음 실행", MAX_CONSECUTIVE_FAILURES);
                        }
                    }
                });
            }
            pool.shutdown();
            if (!pool.awaitTermination(MAX_RUN_MINUTES, TimeUnit.MINUTES)) pool.shutdownNow();
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        Result result = new Result(targets.size(), updated.get(), failed.get());
        log.info("[국내 수집] {}", result.summary());
        return result;
    }

    @SuppressWarnings("unchecked")
    private void enrichOne(KrStockEntity stock, LocalDateTime now) {
        Map<String, Object> body = kisClient.get(KisApi.STOCK_INFO, Map.of("PRDT_TYPE_CD", PRODUCT_TYPE_STOCK, "PDNO", stock.getStockCode()));
        Object output = body.get("output");
        Map<String, Object> info = output instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        stock.setKsicCode(blankToNull(info.get("std_idst_clsf_cd")));
        stock.setKsicName(blankToNull(info.get("std_idst_clsf_cd_name")));
        stock.setKsicCheckedAt(now);
        krStockRepository.save(stock);
    }

    private static String blankToNull(Object value) {
        if (value == null) return null;
        String s = value.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
