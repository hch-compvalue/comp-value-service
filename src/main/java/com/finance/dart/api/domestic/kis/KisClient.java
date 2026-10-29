package com.finance.dart.api.domestic.kis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.dart.common.component.ConfigComponent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;

/**
 * KIS 국내주식 조회 클라이언트 (조회 전용 — 주문 TR 없음)
 * - 타임아웃: 연결 5초 / 응답 10초
 * - 재시도: 최대 3회, 한도 초과(EGW00201)·5xx·네트워크 오류·토큰 만료(EGW00123, 토큰 재수신 후)만 재시도
 * - 그 외 오류(파라미터 등)는 즉시 실패
 */
@Slf4j
@Component
public class KisClient {

    static final String RT_OK = "0";
    static final String MSG_RATE_LIMIT = "EGW00201";
    static final String MSG_TOKEN_EXPIRED = "EGW00123";
    static final int MAX_RETRY = 3;
    private static final long RETRY_BACKOFF_MS = 1_000;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final String CUSTOMER_TYPE_PERSONAL = "P";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE = new ParameterizedTypeReference<>() {};

    private final KisTokenProvider tokenProvider;
    private final KisRateLimiter rateLimiter;
    private final ConfigComponent configComponent;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final long retryBackoffMs;

    @Value("${app.kis.base-url}")
    private String baseUrl;

    @Autowired
    public KisClient(KisTokenProvider tokenProvider, KisRateLimiter rateLimiter,
                     ConfigComponent configComponent, ObjectMapper objectMapper) {
        this(tokenProvider, rateLimiter, configComponent, objectMapper, defaultRestTemplate(), RETRY_BACKOFF_MS);
    }

    /** 테스트용 (RestTemplate·대기시간 주입) */
    KisClient(KisTokenProvider tokenProvider, KisRateLimiter rateLimiter, ConfigComponent configComponent,
              ObjectMapper objectMapper, RestTemplate restTemplate, long retryBackoffMs) {
        this.tokenProvider = tokenProvider;
        this.rateLimiter = rateLimiter;
        this.configComponent = configComponent;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
        this.retryBackoffMs = retryBackoffMs;
    }

    /**
     * 조회 (GET) — 성공(rt_cd=0) 응답 본문 전체 반환
     * @throws KisApiException 키 미설정, 재시도 후에도 실패
     */
    public Map<String, Object> get(KisApi api, Map<String, String> params) {
        return get(api, params, MAX_RETRY);
    }

    /**
     * 한 번만 조회 (재시도 없음) — 헬스체크처럼 빨리 실패를 알아야 할 때
     * @throws KisApiException 키 미설정, 실패
     */
    public Map<String, Object> getOnce(KisApi api, Map<String, String> params) {
        return get(api, params, 1);
    }

    private Map<String, Object> get(KisApi api, Map<String, String> params, int maxAttempts) {
        String appKey = configComponent.getKisAppKey();
        String appSecret = configComponent.getKisAppSecret();
        if (isBlank(appKey) || isBlank(appSecret)) {
            throw new KisApiException("KIS_APP_KEY / KIS_APP_SECRET 미설정");
        }

        String last = "";
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            rateLimiter.acquire();
            try {
                Map<String, Object> body = restTemplate.exchange(uri(api, params), HttpMethod.GET,
                        new HttpEntity<>(headers(api, appKey, appSecret)), MAP_TYPE).getBody();
                if (body != null && RT_OK.equals(body.get("rt_cd"))) {
                    return body;
                }
                last = describe(body);
                if (!isRetryable(body)) break;
            } catch (HttpStatusCodeException e) {
                Map<String, Object> body = parse(e.getResponseBodyAsString());
                last = "HTTP " + e.getStatusCode().value() + " " + describe(body);
                // KIS는 한도 초과를 HTTP 500 + EGW00201 로 응답
                if (!isRetryable(body) && !e.getStatusCode().is5xxServerError()) break;
            } catch (ResourceAccessException e) {
                last = "네트워크 오류: " + e.getMessage();
            } catch (KisApiException e) {
                last = e.getMessage();      // trader 토큰 수신 실패 — 일시적일 수 있어 재시도
            }
            log.warn("[KIS] {} 실패 ({}/{}): {}", api.name(), attempt, maxAttempts, last);
            if (attempt < maxAttempts) sleep(retryBackoffMs * attempt);
        }
        throw new KisApiException("KIS " + api.name() + " 실패: " + last);
    }

    /** 한도 초과·토큰 만료만 재시도. 토큰 만료면 캐시를 비워 다음 시도에서 trader에 다시 요청 */
    private boolean isRetryable(Map<String, Object> body) {
        Object msgCd = body == null ? null : body.get("msg_cd");
        if (MSG_TOKEN_EXPIRED.equals(msgCd)) {
            tokenProvider.invalidate();
            return true;
        }
        return MSG_RATE_LIMIT.equals(msgCd);
    }

    private URI uri(KisApi api, Map<String, String> params) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrl + api.getPath());
        params.forEach(builder::queryParam);
        return builder.encode().build().toUri();
    }

    private HttpHeaders headers(KisApi api, String appKey, String appSecret) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(tokenProvider.get());
        headers.set("appkey", appKey);
        headers.set("appsecret", appSecret);
        headers.set("tr_id", api.getTrId());
        headers.set("custtype", CUSTOMER_TYPE_PERSONAL);
        return headers;
    }

    private Map<String, Object> parse(String raw) {
        if (isBlank(raw)) return null;
        try {
            return objectMapper.readValue(raw, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String describe(Map<String, Object> body) {
        if (body == null) return "(응답 본문 없음)";
        return (body.getOrDefault("msg_cd", "") + " " + body.getOrDefault("msg1", "")).trim();
    }

    private static RestTemplate defaultRestTemplate() {
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        return new RestTemplate(factory);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KisApiException("KIS 재시도 대기 중 인터럽트", e);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
