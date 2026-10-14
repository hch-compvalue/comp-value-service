package com.finance.dart.api.common.controller;


import com.finance.dart.api.common.dto.MgntRedisReqDto;
import com.finance.dart.api.common.dto.MgntRedisResDto;
import com.finance.dart.api.common.dto.log.LogContentDto;
import com.finance.dart.api.common.dto.log.LogFileListDto;
import com.finance.dart.api.common.service.MgntLogService;
import com.finance.dart.api.common.service.MgntRedisService;
import com.finance.dart.common.config.EndPointConfig;
import com.finance.dart.common.dto.CommonResponse;
import com.finance.dart.common.logging.CircularLogBuffer;
import com.finance.dart.common.logging.TransactionLogging;
import com.finance.dart.member.enums.RoleConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

@Slf4j
@RestController
@RequestMapping("mgnt")
@RequiredArgsConstructor
public class ManagementController {

    private final MgntRedisService mgntRedisService;
    private final MgntLogService mgntLogService;


    @EndPointConfig.RequireRole({RoleConstants.ROLE_ADMIN, RoleConstants.ROLE_SUPER_ADMIN})
    @TransactionLogging
    @PostMapping("/redis")
    public ResponseEntity<CommonResponse<MgntRedisResDto>> redisMgnt(@RequestBody MgntRedisReqDto reqDto) {

        MgntRedisResDto mgntRedisResDto = mgntRedisService.reidsMgntService(reqDto);

        return new ResponseEntity<>(new CommonResponse<>(mgntRedisResDto), HttpStatus.OK);
    }

    private final CircularLogBuffer logBuffer;

    /**
     * 로그 실시간 스트리밍 엔드포인트
     * - 애플리케이션 시작 후 버퍼에 쌓인 모든 로그 전송
     * - 이후 실시간 로그 계속 스트리밍
     *
     * 사용법: curl http://localhost:18080/dart/logs/stream
     */
    @EndPointConfig.RequireRole({RoleConstants.ROLE_ADMIN, RoleConstants.ROLE_SUPER_ADMIN})
    @TransactionLogging
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLogs() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);

        log.info("로그 스트리밍 시작 - 클라이언트 연결됨");

        try {
            // 1. 버퍼에 저장된 기존 로그 전체 전송
            List<String> existingLogs = logBuffer.getAllLogs();
            log.info("버퍼에 저장된 로그 {}개 전송 시작", existingLogs.size());

            for (String logLine : existingLogs) {
                emitter.send(SseEmitter.event()
                        .data(logLine)
                        .build());
            }

            // 2. 실시간 로그 구독 설정
            Consumer<String> subscriber = logLine -> {
                try {
                    emitter.send(SseEmitter.event()
                            .data(logLine)
                            .build());
                } catch (IOException e) {
                    log.warn("로그 전송 실패 (클라이언트 연결 끊김): {}", e.getMessage());
                    emitter.completeWithError(e);
                }
            };

            logBuffer.subscribe(subscriber);

            // 3. 연결 종료 시 구독 해제
            emitter.onCompletion(() -> {
                log.info("로그 스트리밍 종료 - 클라이언트 연결 정상 종료");
                logBuffer.unsubscribe(subscriber);
            });

            emitter.onTimeout(() -> {
                log.warn("로그 스트리밍 타임아웃");
                logBuffer.unsubscribe(subscriber);
                emitter.complete();
            });

            emitter.onError((ex) -> {
                log.error("로그 스트리밍 에러: {}", ex.getMessage());
                logBuffer.unsubscribe(subscriber);
            });

        } catch (Exception e) {
            log.error("로그 스트리밍 초기화 실패", e);
            emitter.completeWithError(e);
        }

        return emitter;
    }

    /**
     * 로그파일 목록 조회
     * @return
     */
    @EndPointConfig.RequireRole({RoleConstants.ROLE_ADMIN, RoleConstants.ROLE_SUPER_ADMIN})
    @TransactionLogging
    @GetMapping("/logs")
    public ResponseEntity<CommonResponse<LogFileListDto>> getLogFileList() {
        LogFileListDto logFileList = mgntLogService.getLogFileList();
        return new ResponseEntity<>(new CommonResponse<>(logFileList), HttpStatus.OK);
    }

    /**
     * 로그 파일 내용 조회
     * @param filename
     * @return
     */
    @EndPointConfig.RequireRole({RoleConstants.ROLE_ADMIN, RoleConstants.ROLE_SUPER_ADMIN})
    @TransactionLogging
    @GetMapping("/logs/{filename}")
    public ResponseEntity<CommonResponse<LogContentDto>> getLogContent(@PathVariable(name = "filename") String filename) {

        if(log.isDebugEnabled()) log.debug("/mgnt/logs/{} 거래 요청 - filename: {}", filename, filename);

        try {
            LogContentDto logContent = mgntLogService.getLogContent(filename);

            CommonResponse<LogContentDto> response = new CommonResponse<>(logContent);
            response.setMessage("로그 내용 조회 성공");

            return new ResponseEntity<>(response, HttpStatus.OK);

        } catch (Exception e) {
            log.error("로그 내용 조회 실패 - filename: {}, error: {}", filename, e.getMessage(), e);

            CommonResponse<LogContentDto> errorResponse = new CommonResponse<>();
            errorResponse.setMessage("로그 내용 조회 실패: " + e.getMessage());

            return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
        }

    }


}