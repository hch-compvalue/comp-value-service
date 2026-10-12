package com.finance.dart.cointrade.controller;

import com.finance.dart.cointrade.service.CointradeStrategyService;
import com.finance.dart.common.config.EndPointConfig;
import com.finance.dart.common.dto.CommonResponse;
import com.finance.dart.common.logging.TransactionLogging;
import com.finance.dart.member.enums.RoleConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 코인 자동매매 v5.0 전략 컨트롤러 (화면: 자동매매 대시보드)
 */
@Slf4j
@RestController
@RequestMapping("/api/cointrade/strategy")
@RequiredArgsConstructor
public class CointradeStrategyController {

    private static final int DEFAULT_RUN_LIMIT = 90;

    private final CointradeStrategyService strategyService;

    /** GET /api/cointrade/strategy/status */
    @EndPointConfig.RequireRole({RoleConstants.ROLE_SUPER_ADMIN})
    @GetMapping("/status")
    public ResponseEntity<CommonResponse<Map<String, Object>>> getStatus() {
        return new ResponseEntity<>(new CommonResponse<>(strategyService.getStatus()), HttpStatus.OK);
    }

    /** GET /api/cointrade/strategy/runs?limit=90&mode=LIVE */
    @EndPointConfig.RequireRole({RoleConstants.ROLE_SUPER_ADMIN})
    @GetMapping("/runs")
    public ResponseEntity<CommonResponse<Map<String, Object>>> getRuns(
            @RequestParam(defaultValue = "" + DEFAULT_RUN_LIMIT) int limit,
            @RequestParam(required = false) String mode) {
        return new ResponseEntity<>(new CommonResponse<>(strategyService.getRuns(limit, mode)), HttpStatus.OK);
    }

    /** PUT /api/cointrade/strategy/settings  body: {mode?, capitalKrw?, runHour?} */
    @EndPointConfig.RequireRole({RoleConstants.ROLE_SUPER_ADMIN})
    @TransactionLogging
    @PutMapping("/settings")
    public ResponseEntity<CommonResponse<Map<String, Object>>> updateSettings(@RequestBody Map<String, Object> body) {
        return new ResponseEntity<>(new CommonResponse<>(strategyService.updateSettings(body)), HttpStatus.OK);
    }

    /** POST /api/cointrade/strategy/run  (현재 모드로 즉시 1회) */
    @EndPointConfig.RequireRole({RoleConstants.ROLE_SUPER_ADMIN})
    @TransactionLogging
    @PostMapping("/run")
    public ResponseEntity<CommonResponse<Map<String, Object>>> runNow() {
        return new ResponseEntity<>(new CommonResponse<>(strategyService.runNow()), HttpStatus.OK);
    }
}
