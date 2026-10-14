package com.finance.dart.api.common.controller;

import com.finance.dart.api.common.dto.schedule.ScheduleJobDto;
import com.finance.dart.api.common.dto.schedule.ScheduleJobUpdateReqDto;
import com.finance.dart.api.common.dto.schedule.ScheduleRunHistoryDto;
import com.finance.dart.api.common.dto.schedule.ScheduleRunReqDto;
import com.finance.dart.api.common.service.schedule.ScheduleManagerService;
import com.finance.dart.common.config.EndPointConfig;
import com.finance.dart.common.dto.CommonResponse;
import com.finance.dart.common.logging.TransactionLogging;
import com.finance.dart.member.enums.RoleConstants;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 스케줄 관리 (관리 > 스케줄 관리 화면, 슈퍼관리자 전용)
 */
@Slf4j
@AllArgsConstructor
@RestController
@RequestMapping("schd")
public class ScheduleController {

    /** 이력 조회 기본 건수 */
    private static final String DEFAULT_HISTORY_LIMIT = "20";

    private final ScheduleManagerService scheduleManagerService;

    /**
     * 스케줄 작업 목록 + 상태 (주기, 사용여부, 실행중, 다음 실행, 마지막 실행)
     */
    @EndPointConfig.RequireRole({RoleConstants.ROLE_SUPER_ADMIN})
    @GetMapping("/jobs")
    public ResponseEntity<CommonResponse<List<ScheduleJobDto>>> getJobs() {
        return new ResponseEntity<>(new CommonResponse<>(scheduleManagerService.getJobs()), HttpStatus.OK);
    }

    /**
     * 실행 주기·사용 여부 변경 (재배포 없이 즉시 반영)
     */
    @EndPointConfig.RequireRole({RoleConstants.ROLE_SUPER_ADMIN})
    @TransactionLogging
    @PutMapping("/jobs/{jobKey}")
    public ResponseEntity<CommonResponse<ScheduleJobDto>> updateJob(
            @PathVariable("jobKey") String jobKey,
            @RequestBody ScheduleJobUpdateReqDto reqBody) {

        ScheduleJobDto result = scheduleManagerService.updateJob(jobKey, reqBody.getCronExpression(), reqBody.getEnabled());

        return new ResponseEntity<>(new CommonResponse<>(result), HttpStatus.OK);
    }

    /**
     * 지금 실행 (이미 실행 중이면 거절)
     */
    @EndPointConfig.RequireRole({RoleConstants.ROLE_SUPER_ADMIN})
    @TransactionLogging
    @PostMapping("/jobs/{jobKey}/run")
    public ResponseEntity<CommonResponse<Void>> runJob(
            @PathVariable("jobKey") String jobKey,
            @RequestBody(required = false) ScheduleRunReqDto reqBody) {

        scheduleManagerService.runNow(jobKey, reqBody == null ? null : reqBody.getMaxCount());

        return new ResponseEntity<>(new CommonResponse<>(), HttpStatus.OK);
    }

    /**
     * 작업별 최근 실행 이력
     */
    @EndPointConfig.RequireRole({RoleConstants.ROLE_SUPER_ADMIN})
    @GetMapping("/jobs/{jobKey}/history")
    public ResponseEntity<CommonResponse<List<ScheduleRunHistoryDto>>> getHistory(
            @PathVariable("jobKey") String jobKey,
            @RequestParam(name = "limit", defaultValue = DEFAULT_HISTORY_LIMIT) int limit) {

        return new ResponseEntity<>(new CommonResponse<>(scheduleManagerService.getHistory(jobKey, limit)), HttpStatus.OK);
    }

}
