package com.finance.dart.api.domestic.controller;

import com.finance.dart.api.abroad.dto.fmp.chart.StockPriceVolumeResDto;
import com.finance.dart.api.abroad.dto.fmp.chart.StockPriceVolumeReqDto;
import com.finance.dart.api.domestic.dto.KrStockProfile;
import com.finance.dart.api.domestic.dto.KrStockSearchItem;
import com.finance.dart.api.domestic.service.KrStockQueryService;
import com.finance.dart.common.dto.CommonResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 국내 종목 조회 (ROADMAP_domestic 5-B 종목 분석) — 미국 검색·기업정보·차트와 같은 응답 형식, 로그인 사용자 (미국과 같은 권한)
 */
@RestController
@RequestMapping("kr/stock")
@RequiredArgsConstructor
public class KrStockController {

    private final KrStockQueryService queryService;

    /** 종목명 일부 또는 종목코드 앞부분 */
    @GetMapping("/search")
    public ResponseEntity<CommonResponse<List<KrStockSearchItem>>> search(@RequestParam("q") String q) {
        return new ResponseEntity<>(new CommonResponse<>(queryService.search(q)), HttpStatus.OK);
    }

    /** 기업정보 (없는 종목이면 response null) */
    @GetMapping("/profile")
    public ResponseEntity<CommonResponse<KrStockProfile>> profile(@RequestParam("symbol") String symbol) {
        return new ResponseEntity<>(new CommonResponse<>(queryService.profile(symbol).orElse(null)), HttpStatus.OK);
    }

    /** 일봉 (미국 /abroad/chart/stock-price-volume 와 같은 요청·응답) */
    @PostMapping("/chart")
    public ResponseEntity<CommonResponse<List<StockPriceVolumeResDto>>> chart(@RequestBody StockPriceVolumeReqDto req) {
        LocalDate from = LocalDate.parse(req.getFrom());
        LocalDate to = req.getTo() != null && !req.getTo().isBlank() ? LocalDate.parse(req.getTo()) : null;
        return new ResponseEntity<>(new CommonResponse<>(queryService.chart(req.getSymbol(), from, to)), HttpStatus.OK);
    }
}
