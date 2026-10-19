package com.finance.dart.cointrade.controller;

import com.finance.dart.cointrade.dto.CointradeTradeHistoryDto;
import com.finance.dart.cointrade.service.CointradeConfigService;
import com.finance.dart.common.config.EndPointConfig;
import com.finance.dart.common.dto.CommonResponse;
import com.finance.dart.common.logging.TransactionLogging;
import com.finance.dart.member.enums.RoleConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 코인 자동매매 거래 기록 조회 컨트롤러
 * (v5.0 전략 전환 후 레거시 설정/매매/스캐너/모의투자 API 제거 — 거래 기록 조회만 유지)
 */
@Slf4j
@RestController
@RequestMapping("/api/cointrade")
@RequiredArgsConstructor
public class CointradeConfigController {

    private final CointradeConfigService cointradeConfigService;

    /**
     * 거래 기록 조회 (페이징)
     * GET /api/cointrade/history
     * Query Params: startDate, endDate, coinCode, tradeType, market(CRYPTO|KRX), includeLegacy(기본 false = 현재 전략 기록만), page, size
     */
    @EndPointConfig.RequireRole({RoleConstants.ROLE_SUPER_ADMIN})
    @TransactionLogging
    @GetMapping("/history")
    public ResponseEntity<CommonResponse<Page<CointradeTradeHistoryDto>>> getTradeHistory(
            @RequestParam(required = false, name = "startDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false, name = "endDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            @RequestParam(required = false, name = "coinCode") String coinCode,
            @RequestParam(required = false, name = "tradeType") String tradeType,
            @RequestParam(required = false, name = "market") String market,
            @RequestParam(defaultValue = "false", name = "includeLegacy") boolean includeLegacy,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        log.info("거래 기록 조회 요청 - startDate: {}, endDate: {}, coinCode: {}, tradeType: {}, market: {}, includeLegacy: {}, page: {}",
                startDate, endDate, coinCode, tradeType, market, includeLegacy, pageable.getPageNumber());

        Page<CointradeTradeHistoryDto> history = cointradeConfigService.getTradeHistory(
                startDate, endDate, coinCode, tradeType, market, includeLegacy, pageable);

        return new ResponseEntity<>(new CommonResponse<>(history), HttpStatus.OK);
    }

    /**
     * 거래 기록에 존재하는 코인 코드 목록 조회 (중복 제거, 오름차순)
     * GET /api/cointrade/history/codes
     * 거래 기록 화면의 종목 필터 선택지로 사용
     */
    @EndPointConfig.RequireRole({RoleConstants.ROLE_SUPER_ADMIN})
    @TransactionLogging
    @GetMapping("/history/codes")
    public ResponseEntity<CommonResponse<List<String>>> getTradeHistoryCoinCodes() {
        List<String> coinCodes = cointradeConfigService.getTradeHistoryCoinCodes();
        log.info("거래 기록 코인 코드 목록 조회 - {}개", coinCodes.size());
        return new ResponseEntity<>(new CommonResponse<>(coinCodes), HttpStatus.OK);
    }
}
