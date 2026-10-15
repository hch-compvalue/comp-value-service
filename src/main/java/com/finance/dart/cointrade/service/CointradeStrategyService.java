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
import java.util.regex.Pattern;

/**
 * 자동매매 전략 프록시 서비스 (v5.1: 코인 btc_trend · 주식 etf3_trend_v1)
 * 트레이더(FastAPI) /api/strategy/*?strategy=<id> 로 전달한다. 전략 ID 미지정 시 btc_trend.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CointradeStrategyService {

    /** 즉시 실행은 주문 체결 확인까지 기다린다 (주식은 종목 3개 × 체결 확인·잔량 취소) */
    private static final int RUN_READ_TIMEOUT_MS = 180_000;

    private static final Pattern STRATEGY_ID = Pattern.compile(CoinTraderProgramConfig.STRATEGY_ID_PATTERN);

    private final HttpClientComponent httpClientComponent;

    @Value("${app.local}")
    private boolean isLocal;

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE = new ParameterizedTypeReference<>() {};

    /** 등록 전략(프리셋) 목록 + 전략별 운영 설정 */
    public Map<String, Object> getStrategies() {
        return call(CoinTraderProgramConfig.API_URI_STRATEGY_LIST, HttpMethod.GET, null);
    }

    /** 현재 모드·설정·오늘 신호·장부·계좌 대조 */
    public Map<String, Object> getStatus(String strategy) {
        return call(withStrategy(CoinTraderProgramConfig.API_URI_STRATEGY_STATUS, strategy), HttpMethod.GET, null);
    }

    /** 실행 기록 */
    public Map<String, Object> getRuns(String strategy, int limit, String mode) {
        String uri = withStrategy(CoinTraderProgramConfig.API_URI_STRATEGY_RUNS, strategy) + "&limit=" + limit
                + (mode == null || mode.isBlank() ? "" : "&mode=" + mode);
        return call(uri, HttpMethod.GET, null);
    }

    /** 모드 / 투자금 / 실행 시각 / 일시 중지 종목 변경 */
    public Map<String, Object> updateSettings(String strategy, Map<String, Object> body) {
        log.info("전략 설정 변경 요청 [{}]: {}", strategy, body);
        return call(withStrategy(CoinTraderProgramConfig.API_URI_STRATEGY_SETTINGS, strategy), HttpMethod.PUT, body);
    }

    /** 즉시 1회 실행 */
    public Map<String, Object> runNow(String strategy) {
        log.info("전략 즉시 실행 요청 [{}]", strategy);
        return callLong(withStrategy(CoinTraderProgramConfig.API_URI_STRATEGY_RUN, strategy), null);
    }

    /** 긴급 전량 매도 + 중지 (body: {"confirm": "매도"}) */
    public Map<String, Object> emergencyExit(String strategy, Map<String, Object> body) {
        log.warn("전략 긴급 전량 매도 요청 [{}]", strategy);
        return callLong(withStrategy(CoinTraderProgramConfig.API_URI_STRATEGY_EMERGENCY_EXIT, strategy), body);
    }

    private String withStrategy(String uri, String strategy) {
        String id = strategy == null || strategy.isBlank() ? CoinTraderProgramConfig.DEFAULT_STRATEGY_ID : strategy;
        if (!STRATEGY_ID.matcher(id).matches()) {
            throw new BizException("잘못된 전략 ID: " + id);
        }
        return uri + "?strategy=" + id;
    }

    private Map<String, Object> call(String uri, HttpMethod method, Object body) {
        try {
            return httpClientComponent.exchangeSync(buildUrl(uri), method, null, body, MAP_TYPE).getBody();
        } catch (HttpStatusCodeException e) {
            throw new BizException(extractDetail(e));
        }
    }

    private Map<String, Object> callLong(String uri, Object body) {
        try {
            return httpClientComponent.exchangeSyncWithTimeout(buildUrl(uri), HttpMethod.POST, null, body, MAP_TYPE,
                    RUN_READ_TIMEOUT_MS).getBody();
        } catch (HttpStatusCodeException e) {
            throw new BizException(extractDetail(e));
        }
    }

    /** FastAPI HTTPException 응답 {"detail": "..."} 에서 메시지 추출 */
    private String extractDetail(HttpStatusCodeException e) {
        String raw = e.getResponseBodyAsString();
        int idx = raw.indexOf("\"detail\":\"");
        if (idx < 0) return "Trader 오류: " + e.getStatusCode();
        int start = idx + "\"detail\":\"".length();
        int end = raw.indexOf('"', start);
        return end > start ? raw.substring(start, end) : raw;
    }

    private String buildUrl(String uri) {
        String baseUrl = isLocal ? CoinTraderProgramConfig.localHost : CoinTraderProgramConfig.prodHost;
        return baseUrl + "/" + uri;
    }
}
