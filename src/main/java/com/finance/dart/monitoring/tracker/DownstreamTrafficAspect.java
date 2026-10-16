package com.finance.dart.monitoring.tracker;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * 다운스트림 서비스 호출 AOP 추적
 * RequestTrafficTracker.isInUserRequest() == true 일 때만 카운트
 * → @Scheduled 모니터링 헬스체크는 자동 제외
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class DownstreamTrafficAspect {

    private final DownstreamTrafficTracker tracker;

    @PostConstruct
    public void init() {
        log.info("[TrafficAspect] 초기화 완료 — DB/Redis/Trader/ML 트래픽 AOP 추적 활성화");
    }

    /** Spring Data Repository 호출 → DB (PostgreSQL) 트래픽 */
    @Around("execution(* org.springframework.data.repository.Repository+.*(..))")
    public Object trackDb(ProceedingJoinPoint pjp) throws Throwable {
        if (RequestTrafficTracker.isInUserRequest()) {
            tracker.trackDb();
        }
        return pjp.proceed();
    }

    /** RedisComponent 호출 → Redis 트래픽 */
    @Around("execution(* com.finance.dart.common.component.RedisComponent.*(..))")
    public Object trackRedis(ProceedingJoinPoint pjp) throws Throwable {
        if (RequestTrafficTracker.isInUserRequest()) {
            tracker.trackRedis();
            log.debug("[TrafficAspect] Redis 호출 감지: {}", pjp.getSignature().toShortString());
        }
        return pjp.proceed();
    }

    /**
     * Cointrade 서비스 패키지 호출 → Trader(자동매매) 트래픽
     * (HttpClientComponent.exchangeSync 로 trader-service API 호출)
     */
    @Around("execution(* com.finance.dart.cointrade.service..*.*(..))")
    public Object trackTrader(ProceedingJoinPoint pjp) throws Throwable {
        if (RequestTrafficTracker.isInUserRequest()) {
            tracker.trackTrader();
            log.debug("[TrafficAspect] Trader 호출 감지: {}", pjp.getSignature().toShortString());
        }
        return pjp.proceed();
    }

    /** Stock predictor 서비스 패키지 호출 → ML Predict 트래픽 */
    @Around("execution(* com.finance.dart.stockpredictor.service..*.*(..))")
    public Object trackMl(ProceedingJoinPoint pjp) throws Throwable {
        if (RequestTrafficTracker.isInUserRequest()) {
            tracker.trackMl();
            log.debug("[TrafficAspect] ML 호출 감지: {}", pjp.getSignature().toShortString());
        }
        return pjp.proceed();
    }
}
