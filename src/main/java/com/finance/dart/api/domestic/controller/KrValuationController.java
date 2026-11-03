package com.finance.dart.api.domestic.controller;

import com.finance.dart.api.common.dto.CompanySharePriceResult;
import com.finance.dart.api.common.dto.evaluation.StockEvaluationResponse;
import com.finance.dart.api.domestic.valuation.KrEvaluationService;
import com.finance.dart.api.domestic.valuation.KrValuationService;
import com.finance.dart.common.dto.CommonResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 국내 주당가치 계산·6단계 평가 (ROADMAP_domestic 3단계 확인용 → 5-B 종목 분석 화면, 로그인 사용자 — 미국과 같은 권한)
 */
@RestController
@RequestMapping("kr/valuation")
@RequiredArgsConstructor
public class KrValuationController {

    private final KrValuationService valuationService;
    private final KrEvaluationService evaluationService;

    /**
     * @param refresh true 면 캐시 무시하고 다시 계산
     */
    @GetMapping("/{stockCode}")
    public ResponseEntity<CommonResponse<CompanySharePriceResult>> calculate(@PathVariable String stockCode,
                                                                            @RequestParam(defaultValue = "false") boolean refresh) {
        return new ResponseEntity<>(new CommonResponse<>(valuationService.calculate(stockCode, refresh)), HttpStatus.OK);
    }

    /**
     * 6단계 평가 (미국 /main/evaluate/stocks 한 건과 같은 응답) — 계산 제외 종목은 response null, message 에 사유
     */
    @GetMapping("/{stockCode}/evaluation")
    public ResponseEntity<CommonResponse<StockEvaluationResponse>> evaluate(@PathVariable String stockCode) {
        KrEvaluationService.Result result = evaluationService.evaluate(stockCode);
        CommonResponse<StockEvaluationResponse> body = new CommonResponse<>(result.response());
        if (result.response() == null) body.setMessage("계산 제외: " + result.excludedReason());
        return new ResponseEntity<>(body, HttpStatus.OK);
    }
}
