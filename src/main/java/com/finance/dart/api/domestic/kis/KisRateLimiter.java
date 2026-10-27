package com.finance.dart.api.domestic.kis;

import org.springframework.stereotype.Component;

/**
 * KIS 호출 간격 제한
 * - 실전 한도 초당 20건을 trader(자동매매)와 공유 → 스프링은 초당 8건 이하로 남겨둔다
 * - 호출 간 최소 간격을 지키도록 대기 (스레드 간 공유)
 */
@Component
public class KisRateLimiter {

    static final long MIN_INTERVAL_MS = 125;    // 초당 8건

    private long lastCallAt = 0;

    public synchronized void acquire() {
        long waitMs = lastCallAt + MIN_INTERVAL_MS - System.currentTimeMillis();
        if (waitMs > 0) {
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new KisApiException("KIS 호출 대기 중 인터럽트", e);
            }
        }
        lastCallAt = System.currentTimeMillis();
    }
}
