package com.finance.dart.api.domestic.controller;

import com.finance.dart.api.common.dto.CompanySharePriceResult;
import com.finance.dart.api.domestic.valuation.KrValuationService;
import com.finance.dart.common.config.EndPointConfig;
import com.finance.dart.member.enums.RoleConstants;
import com.finance.dart.common.dto.CommonResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 국내 주당가치 계산 (ROADMAP_domestic 3단계 — 화면 연결 전 확인용, 관리자 전용)
 */
@RestController
@RequestMapping("kr/valuation")
@RequiredArgsConstructor
public class KrValuationController {

    private final KrValuationService valuationService;

    /**
     * @param refresh true 면 캐시 무시하고 다시 계산
     */
    @EndPointConfig.RequireRole({RoleConstants.ROLE_SUPER_ADMIN})
    @GetMapping("/{stockCode}")
    public ResponseEntity<CommonResponse<CompanySharePriceResult>> calculate(@PathVariable String stockCode,
                                                                            @RequestParam(defaultValue = "false") boolean refresh) {
        return new ResponseEntity<>(new CommonResponse<>(valuationService.calculate(stockCode, refresh)), HttpStatus.OK);
    }
}
