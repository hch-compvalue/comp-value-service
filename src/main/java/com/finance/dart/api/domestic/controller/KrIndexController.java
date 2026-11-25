package com.finance.dart.api.domestic.controller;

import com.finance.dart.api.abroad.dto.fmp.chart.HistoricalIndexResDto;
import com.finance.dart.api.domestic.enums.KrExchange;
import com.finance.dart.api.domestic.service.KrIndexChartService;
import com.finance.dart.common.dto.CommonResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * 국내 지수 조회 — 홈 "국내 주요 지수" (미국 /abroad/chart/historical-index 와 같은 응답, 로그인 사용자)
 */
@RestController
@RequestMapping("kr/index")
@RequiredArgsConstructor
public class KrIndexController {

    private final KrIndexChartService chartService;

    /**
     * @param symbol KOSPI / KOSDAQ (그 외는 빈 목록)
     * @param from   조회 시작일 (yyyy-MM-dd)
     */
    @GetMapping("/chart")
    public ResponseEntity<CommonResponse<List<HistoricalIndexResDto>>> chart(
            @RequestParam("symbol") String symbol,
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from) {
        List<HistoricalIndexResDto> body = Arrays.stream(KrExchange.values())
                .filter(ex -> ex.name().equalsIgnoreCase(symbol))
                .findFirst()
                .map(ex -> chartService.chart(ex, from))
                .orElse(List.of());
        return new ResponseEntity<>(new CommonResponse<>(body), HttpStatus.OK);
    }
}
