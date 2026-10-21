package com.finance.dart.monitoring.controller;

import com.finance.dart.common.config.EndPointConfig;
import com.finance.dart.common.logging.TransactionLogging;
import com.finance.dart.member.enums.RoleConstants;
import com.finance.dart.monitoring.buffer.MonitoringEventBuffer;
import com.finance.dart.monitoring.dto.ApiRequestLogDto;
import com.finance.dart.monitoring.dto.MonitoringSnapshotDto;
import com.finance.dart.monitoring.dto.PodStatusDto;
import com.finance.dart.monitoring.dto.ExternalApiToggleReqDto;
import com.finance.dart.monitoring.dto.ServiceStatusDto;
import com.finance.dart.monitoring.service.ExternalApiHealthService;
import com.finance.dart.common.exception.BizException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import com.finance.dart.monitoring.service.PodStatusService;
import com.finance.dart.common.dto.CommonResponse;
import org.springframework.http.ResponseEntity;
import com.finance.dart.monitoring.dto.TradeEventDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 모니터링 SSE 스트리밍 엔드포인트
 * ManagementController.java SSE 패턴 복제 — named events 사용
 */
@Slf4j
@RestController
@RequestMapping("monitoring")
@RequiredArgsConstructor
public class MonitoringController {

    private final MonitoringEventBuffer eventBuffer;
    private final ObjectMapper objectMapper;
    private final PodStatusService podStatusService;
    private final ExternalApiHealthService externalApiHealthService;

    /**
     * 외부 API 모니터링 사용 여부 변경 (끄면 헬스체크 호출을 멈추고 '사용 안 함'으로 표시)
     */
    @EndPointConfig.RequireRole({RoleConstants.ROLE_SUPER_ADMIN})
    @TransactionLogging
    @PutMapping("/external-apis/{apiName}")
    public ResponseEntity<CommonResponse<ServiceStatusDto>> setExternalApiEnabled(
            @PathVariable("apiName") String apiName,
            @RequestBody ExternalApiToggleReqDto reqBody) {
        if (reqBody == null || reqBody.getEnabled() == null) {
            throw new BizException("enabled 값이 필요합니다.");
        }
        return ResponseEntity.ok(new CommonResponse<>(externalApiHealthService.setEnabled(apiName, reqBody.getEnabled())));
    }

    /**
     * 클러스터 전체 파드 상태 (실시간 대시보드 '파드' 탭)
     */
    @EndPointConfig.RequireRole({RoleConstants.ROLE_SUPER_ADMIN})
    @GetMapping("/pods")
    public ResponseEntity<CommonResponse<List<PodStatusDto>>> pods() {
        return ResponseEntity.ok(new CommonResponse<>(podStatusService.getPods()));
    }

    /**
     * SSE 스트림 엔드포인트
     * named events: "snapshot", "trade"
     *
     * 사용법: curl http://localhost:18080/dart/monitoring/stream
     */
    @EndPointConfig.RequireRole({RoleConstants.ROLE_SUPER_ADMIN})
    @TransactionLogging
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);

        log.info("모니터링 SSE 스트리밍 시작 - 클라이언트 연결됨");

        try {
            // 1. 최신 snapshot이 있으면 즉시 전송
            MonitoringSnapshotDto latestSnapshot = eventBuffer.getLatestSnapshot();
            if (latestSnapshot != null) {
                emitter.send(SseEmitter.event()
                        .name("snapshot")
                        .data(objectMapper.writeValueAsString(latestSnapshot))
                        .build());
            }

            // 2. 실시간 snapshot 구독
            Consumer<MonitoringSnapshotDto> snapshotSubscriber = snapshot -> {
                try {
                    emitter.send(SseEmitter.event()
                            .name("snapshot")
                            .data(objectMapper.writeValueAsString(snapshot))
                            .build());
                } catch (IOException e) {
                    log.debug("snapshot 전송 실패 (클라이언트 연결 끊김)");
                    emitter.completeWithError(e);
                }
            };

            // 3. 실시간 trade 구독
            Consumer<TradeEventDto> tradeSubscriber = trade -> {
                try {
                    emitter.send(SseEmitter.event()
                            .name("trade")
                            .data(objectMapper.writeValueAsString(trade))
                            .build());
                } catch (IOException e) {
                    log.debug("trade 전송 실패 (클라이언트 연결 끊김)");
                    emitter.completeWithError(e);
                }
            };

            // 4. 실시간 traffic 구독 (1초 간격, 서비스별 개별 카운트)
            Consumer<Map<String, Integer>> trafficSubscriber = trafficData -> {
                try {
                    emitter.send(SseEmitter.event()
                            .name("traffic")
                            .data(objectMapper.writeValueAsString(trafficData))
                            .build());
                } catch (IOException e) {
                    log.debug("traffic 전송 실패 (클라이언트 연결 끊김)");
                    emitter.completeWithError(e);
                }
            };

            // 5. 실시간 api-log 구독 (1초 간격, 개별 API 요청 로그 배치)
            Consumer<List<ApiRequestLogDto>> apiLogSubscriber = logs -> {
                try {
                    emitter.send(SseEmitter.event()
                            .name("api-log")
                            .data(objectMapper.writeValueAsString(logs))
                            .build());
                } catch (IOException e) {
                    log.debug("api-log 전송 실패 (클라이언트 연결 끊김)");
                    emitter.completeWithError(e);
                }
            };

            eventBuffer.subscribeSnapshot(snapshotSubscriber);
            eventBuffer.subscribeTrade(tradeSubscriber);
            eventBuffer.subscribeTraffic(trafficSubscriber);
            eventBuffer.subscribeApiLog(apiLogSubscriber);

            // 6. 연결 종료 시 구독 해제
            Runnable cleanup = () -> {
                eventBuffer.unsubscribeSnapshot(snapshotSubscriber);
                eventBuffer.unsubscribeTrade(tradeSubscriber);
                eventBuffer.unsubscribeTraffic(trafficSubscriber);
                eventBuffer.unsubscribeApiLog(apiLogSubscriber);
            };

            emitter.onCompletion(() -> {
                log.info("모니터링 SSE 스트리밍 종료 - 클라이언트 연결 정상 종료");
                cleanup.run();
            });

            emitter.onTimeout(() -> {
                log.warn("모니터링 SSE 스트리밍 타임아웃");
                cleanup.run();
                emitter.complete();
            });

            emitter.onError(ex -> {
                log.error("모니터링 SSE 스트리밍 에러: {}", ex.getMessage());
                cleanup.run();
            });

        } catch (Exception e) {
            log.error("모니터링 SSE 스트리밍 초기화 실패", e);
            emitter.completeWithError(e);
        }

        return emitter;
    }
}
