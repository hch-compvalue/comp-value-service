package com.finance.dart.cointrade.service;

import com.finance.dart.cointrade.consts.CoinTraderProgramConfig;
import com.finance.dart.common.component.HttpClientComponent;
import com.finance.dart.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;

import java.util.Map;

/**
 * 코인 자동매매 v5.0 전략(BTC 50일선 추세 + 목표변동성) 프록시 서비스
 * CoinTrader(FastAPI) /api/strategy/* 로 전달한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CointradeStrategyService {

    /** 즉시 실행은 주문 체결 확인까지 기다리므로 기본보다 긴 읽기 타임아웃 */
    private static final int RUN_READ_TIMEOUT_MS = 90_000;

    private final HttpClientComponent httpClientComponent;

    @Value("${app.local}")
    private boolean isLocal;

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE = new ParameterizedTypeReference<>() {};

    /** 현재 모드·설정·오늘 신호·장부·계좌 대조 */
    public Map<String, Object> getStatus() {
        return call(CoinTraderProgramConfig.API_URI_STRATEGY_STATUS, HttpMethod.GET, null);
    }

    /** 실행 기록 */
    public Map<String, Object> getRuns(int limit, String mode) {
        String uri = CoinTraderProgramConfig.API_URI_STRATEGY_RUNS + "?limit=" + limit
                + (mode == null || mode.isBlank() ? "" : "&mode=" + mode);
        return call(uri, HttpMethod.GET, null);
    }

    /** 모드 / 투자금 / 실행 시각 변경 */
    public Map<String, Object> updateSettings(Map<String, Object> body) {
        log.info("전략 설정 변경 요청: {}", body);
        return call(CoinTraderProgramConfig.API_URI_STRATEGY_SETTINGS, HttpMethod.PUT, body);
    }

    /** 즉시 1회 실행 */
    public Map<String, Object> runNow() {
        log.info("전략 즉시 실행 요청");
        try {
            return httpClientComponent.exchangeSyncWithTimeout(buildUrl(CoinTraderProgramConfig.API_URI_STRATEGY_RUN),
                    HttpMethod.POST, null, null, MAP_TYPE, RUN_READ_TIMEOUT_MS).getBody();
        } catch (HttpStatusCodeException e) {
            throw new BizException(extractDetail(e));
        }
    }

    /** 긴급 전량 매도 + 중지 (body: {"confirm": "매도"}) */
    public Map<String, Object> emergencyExit(Map<String, Object> body) {
        log.warn("전략 긴급 전량 매도 요청");
        try {
            return httpClientComponent.exchangeSyncWithTimeout(
                    buildUrl(CoinTraderProgramConfig.API_URI_STRATEGY_EMERGENCY_EXIT),
                    HttpMethod.POST, null, body, MAP_TYPE, RUN_READ_TIMEOUT_MS).getBody();
        } catch (HttpStatusCodeException e) {
            throw new BizException(extractDetail(e));
        }
    }

    private Map<String, Object> call(String uri, HttpMethod method, Object body) {
        try {
            return httpClientComponent.exchangeSync(buildUrl(uri), method, null, body, MAP_TYPE).getBody();
        } catch (HttpStatusCodeException e) {
            throw new BizException(extractDetail(e));
        }
    }

    /** FastAPI HTTPException 응답 {"detail": "..."} 에서 메시지 추출 */
    private String extractDetail(HttpStatusCodeException e) {
        String raw = e.getResponseBodyAsString();
        int idx = raw.indexOf("\"detail\":\"");
        if (idx < 0) return "CoinTrader 오류: " + e.getStatusCode();
        int start = idx + "\"detail\":\"".length();
        int end = raw.indexOf('"', start);
        return end > start ? raw.substring(start, end) : raw;
    }

    private String buildUrl(String uri) {
        String baseUrl = isLocal ? CoinTraderProgramConfig.localHost : CoinTraderProgramConfig.prodHost;
        return baseUrl + "/" + uri;
    }
}
