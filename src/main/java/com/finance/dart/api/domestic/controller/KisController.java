package com.finance.dart.api.domestic.controller;

import com.finance.dart.api.domestic.kis.KisApi;
import com.finance.dart.api.domestic.kis.KisApiException;
import com.finance.dart.api.domestic.kis.KisClient;
import com.finance.dart.common.config.EndPointConfig;
import com.finance.dart.common.dto.CommonResponse;
import com.finance.dart.common.exception.BizException;
import com.finance.dart.member.enums.RoleConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * KIS 연동 점검 (국내주식 로드맵 1단계 — 배포 후 토큰 공유·호출 확인용)
 */
@RestController
@RequestMapping("kis")
@RequiredArgsConstructor
public class KisController {

    /** 점검 종목: 삼성전자 */
    private static final String CHECK_STOCK_CODE = "005930";
    private static final String MARKET_DIV_STOCK = "J";

    private final KisClient kisClient;

    /**
     * 현재가 1건 조회 — trader 토큰 수신 + KIS 호출 경로 점검
     */
    @EndPointConfig.RequireRole({RoleConstants.ROLE_SUPER_ADMIN})
    @GetMapping("/check")
    public ResponseEntity<CommonResponse<Map<String, Object>>> check() {
        long start = System.currentTimeMillis();
        Map<String, Object> body;
        try {
            body = kisClient.get(KisApi.PRICE,
                    Map.of("FID_COND_MRKT_DIV_CODE", MARKET_DIV_STOCK, "FID_INPUT_ISCD", CHECK_STOCK_CODE));
        } catch (KisApiException e) {
            throw new BizException(e.getMessage());
        }
        Map<?, ?> output = body.get("output") instanceof Map<?, ?> m ? m : Map.of();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stockCode", CHECK_STOCK_CODE);
        result.put("price", output.get("stck_prpr"));
        result.put("industry", output.get("bstp_kor_isnm"));
        result.put("elapsedMs", System.currentTimeMillis() - start);
        return new ResponseEntity<>(new CommonResponse<>(result), HttpStatus.OK);
    }
}
