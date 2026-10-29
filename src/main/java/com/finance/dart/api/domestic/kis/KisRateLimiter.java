package com.finance.dart.api.domestic.kis;

import org.springframework.stereotype.Component;

/**
 * KIS 호출 간격 제한
 * - 실전 한도 초당 20건을 trader(자동매매)와 공유 → 스프링은 초당 6건 이하로 남겨둔다
 * - 10-29 첫 일봉 적재에서 초당 8건에도 EGW00201(한도 초과)이 호출의 1.8% 발생 → 6건으로 하향
 * - 호출 간 최소 간격을 지키도록 대기 (스레드 간 공유)
 */
@Component
public class KisRateLimiter {

    static final long MIN_INTERVAL_MS = 167;    // 초당 6건

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
