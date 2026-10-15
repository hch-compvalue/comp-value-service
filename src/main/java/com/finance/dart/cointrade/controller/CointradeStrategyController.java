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
 * 자동매매 전략 컨트롤러 (화면: 자동매매 대시보드 — 코인/주식 탭)
 * 모든 API는 ?strategy=<id> 를 받는다 (생략 시 btc_trend).
 */
@Slf4j
@RestController
@RequestMapping("/api/cointrade/strategy")
@RequiredArgsConstructor
public class CointradeStrategyController {

    private static final int DEFAULT_RUN_LIMIT = 90;

    private final CointradeStrategyService strategyService;

    /** GET /api/cointrade/strategy/list  (등록 전략 프리셋 + 전략별 운영 설정) */
    @EndPointConfig.RequireRole({RoleConstants.ROLE_SUPER_ADMIN})
    @GetMapping("/list")
    public ResponseEntity<CommonResponse<Map<String, Object>>> getStrategies() {
        return new ResponseEntity<>(new CommonResponse<>(strategyService.getStrategies()), HttpStatus.OK);
    }

    /** GET /api/cointrade/strategy/status?strategy=btc_trend */
    @EndPointConfig.RequireRole({RoleConstants.ROLE_SUPER_ADMIN})
    @GetMapping("/status")
    public ResponseEntity<CommonResponse<Map<String, Object>>> getStatus(
            @RequestParam(required = false) String strategy) {
        return new ResponseEntity<>(new CommonResponse<>(strategyService.getStatus(strategy)), HttpStatus.OK);
    }

    /** GET /api/cointrade/strategy/runs?strategy=btc_trend&limit=90&mode=LIVE */
    @EndPointConfig.RequireRole({RoleConstants.ROLE_SUPER_ADMIN})
    @GetMapping("/runs")
    public ResponseEntity<CommonResponse<Map<String, Object>>> getRuns(
            @RequestParam(required = false) String strategy,
            @RequestParam(defaultValue = "" + DEFAULT_RUN_LIMIT) int limit,
            @RequestParam(required = false) String mode) {
        return new ResponseEntity<>(new CommonResponse<>(strategyService.getRuns(strategy, limit, mode)), HttpStatus.OK);
    }

    /** PUT /api/cointrade/strategy/settings?strategy=  body: {mode?, capitalKrw?, runHour?, pausedSymbols?} */
    @EndPointConfig.RequireRole({RoleConstants.ROLE_SUPER_ADMIN})
    @TransactionLogging
    @PutMapping("/settings")
    public ResponseEntity<CommonResponse<Map<String, Object>>> updateSettings(
            @RequestParam(required = false) String strategy, @RequestBody Map<String, Object> body) {
        return new ResponseEntity<>(new CommonResponse<>(strategyService.updateSettings(strategy, body)), HttpStatus.OK);
    }

    /** POST /api/cointrade/strategy/run?strategy=  (현재 모드로 즉시 1회) */
    @EndPointConfig.RequireRole({RoleConstants.ROLE_SUPER_ADMIN})
    @TransactionLogging
    @PostMapping("/run")
    public ResponseEntity<CommonResponse<Map<String, Object>>> runNow(@RequestParam(required = false) String strategy) {
        return new ResponseEntity<>(new CommonResponse<>(strategyService.runNow(strategy)), HttpStatus.OK);
    }

    /** POST /api/cointrade/strategy/emergency-exit?strategy=  body: {"confirm": "매도"} */
    @EndPointConfig.RequireRole({RoleConstants.ROLE_SUPER_ADMIN})
    @TransactionLogging
    @PostMapping("/emergency-exit")
    public ResponseEntity<CommonResponse<Map<String, Object>>> emergencyExit(
            @RequestParam(required = false) String strategy, @RequestBody Map<String, Object> body) {
        return new ResponseEntity<>(new CommonResponse<>(strategyService.emergencyExit(strategy, body)), HttpStatus.OK);
    }
}
