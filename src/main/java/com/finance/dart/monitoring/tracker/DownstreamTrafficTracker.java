package com.finance.dart.monitoring.tracker;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 다운스트림 서비스별 트래픽 카운터
 * AOP + WebClient 필터에서 호출, 1초 주기로 읽어서 SSE로 전송
 */
@Component
public class DownstreamTrafficTracker {

    private final AtomicInteger dbCount = new AtomicInteger(0);
    private final AtomicInteger redisCount = new AtomicInteger(0);
    private final AtomicInteger traderCount = new AtomicInteger(0);
    private final AtomicInteger mlCount = new AtomicInteger(0);

    public void trackDb() { dbCount.incrementAndGet(); }
    public void trackRedis() { redisCount.incrementAndGet(); }
    public void trackTrader() { traderCount.incrementAndGet(); }
    public void trackMl() { mlCount.incrementAndGet(); }

    /** 모든 카운터를 읽고 리셋 */
    public Map<String, Integer> getAndResetAll() {
        return Map.of(
                "db", dbCount.getAndSet(0),
                "redis", redisCount.getAndSet(0),
                "trader", traderCount.getAndSet(0),
                "ml", mlCount.getAndSet(0)
        );
    }

    /** 현재 카운터 조회 (리셋 없이 — 디버그용) */
    public Map<String, Integer> peekAll() {
        return Map.of(
                "db", dbCount.get(),
                "redis", redisCount.get(),
                "trader", traderCount.get(),
                "ml", mlCount.get()
        );
    }
}
