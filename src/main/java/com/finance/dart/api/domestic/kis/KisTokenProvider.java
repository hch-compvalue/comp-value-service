package com.finance.dart.api.domestic.kis;

import com.finance.dart.cointrade.consts.CoinTraderProgramConfig;
import com.finance.dart.common.component.ConfigComponent;
import com.finance.dart.common.component.HttpClientComponent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * KIS 접근 토큰 — trader(GET /internal/kis/token)에서 받아 캐시
 * - 토큰 발급은 trader만 한다: 두 서비스가 각자 발급하면 서로 재발급을 반복할 수 있음 (docs/ROADMAP_domestic.md 1단계)
 * - 만료 10분 전부터는 trader에 다시 요청 (trader도 같은 기준으로 재발급)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KisTokenProvider {

    static final String TOKEN_URI = "internal/kis/token";
    static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    private static final long REFRESH_MARGIN_SEC = 600;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE = new ParameterizedTypeReference<>() {};

    private final HttpClientComponent httpClientComponent;
    private final ConfigComponent configComponent;

    @Value("${app.local}")
    private boolean isLocal;

    private String token;
    private long expiresAt;     // epoch 초

    public synchronized String get() {
        if (token == null || nowSec() >= expiresAt - REFRESH_MARGIN_SEC) {
            fetch();
        }
        return token;
    }

    /** KIS가 토큰 만료(EGW00123)를 응답했을 때 — 다음 get()에서 trader에 다시 요청 */
    public synchronized void invalidate() {
        token = null;
        expiresAt = 0;
    }

    private void fetch() {
        String secret = configComponent.getTraderInternalToken();
        if (secret == null || secret.isBlank()) {
            throw new KisApiException("TRADER_INTERNAL_TOKEN 미설정 — trader에서 KIS 토큰을 받을 수 없음");
        }
        Map<String, Object> body;
        try {
            body = httpClientComponent.exchangeSyncWithTimeout(traderUrl(), HttpMethod.GET,
                    Map.of(INTERNAL_TOKEN_HEADER, secret), null, MAP_TYPE, READ_TIMEOUT_MS).getBody();
        } catch (RestClientException e) {
            throw new KisApiException("trader KIS 토큰 조회 실패: " + e.getMessage(), e);
        }
        if (body == null || !(body.get("accessToken") instanceof String t) || !(body.get("expiresAt") instanceof Number exp)) {
            throw new KisApiException("trader KIS 토큰 응답 형식 오류");
        }
        token = t;
        expiresAt = exp.longValue();
        log.info("[KIS] trader에서 접근 토큰 수신 (만료까지 {}분)", (expiresAt - nowSec()) / 60);
    }

    private String traderUrl() {
        return (isLocal ? CoinTraderProgramConfig.localHost : CoinTraderProgramConfig.prodHost) + "/" + TOKEN_URI;
    }

    private static long nowSec() {
        return System.currentTimeMillis() / 1000;
    }
}
