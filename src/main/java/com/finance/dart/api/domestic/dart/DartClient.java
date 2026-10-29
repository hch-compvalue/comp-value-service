package com.finance.dart.api.domestic.dart;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.dart.common.component.ConfigComponent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OpenDart 조회 클라이언트 (국내 재무 수집용 — 구 국내 화면의 FinancialStatementService는 그대로 둔다)
 * - 타임아웃: 연결 5초 / 응답 30초 (재무 응답이 수백 KB)
 * - 재시도: 최대 3회, 네트워크 오류·5xx·시스템 점검(800)·정의되지 않은 오류(900)만
 * - status 013(데이터 없음)은 정상 결과(빈 목록), 020(일일 한도 초과)은 DartLimitExceededException
 * - 호출 간격 200ms(초당 5건, 스레드 공유), 호출 수를 세어 이번 실행 예산을 넘지 않게 한다
 */
@Slf4j
@Component
public class DartClient {

    static final String STATUS_OK = "000";
    static final String STATUS_NO_DATA = "013";
    static final String STATUS_LIMIT_EXCEEDED = "020";
    static final String STATUS_MAINTENANCE = "800";
    static final String STATUS_UNDEFINED_ERROR = "900";
    static final int MAX_RETRY = 3;
    static final long MIN_INTERVAL_MS = 200;
    private static final long RETRY_BACKOFF_MS = 2_000;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final String BASE_URL = "https://opendart.fss.or.kr/api/";

    private final ConfigComponent configComponent;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final long retryBackoffMs;
    private final long minIntervalMs;
    private final AtomicInteger callCount = new AtomicInteger();
    private long lastCallAt = 0;

    /** 응답 (status 000 = rows 있음, 013 = rows 빈 목록) */
    public record Result(String status, Map<String, Object> body, List<Map<String, Object>> rows) {
        public boolean hasData() {
            return !rows.isEmpty();
        }
    }

    @Autowired
    public DartClient(ConfigComponent configComponent, ObjectMapper objectMapper) {
        this(configComponent, objectMapper, defaultRestTemplate(), RETRY_BACKOFF_MS, MIN_INTERVAL_MS);
    }

    /** 테스트용 (RestTemplate·대기시간 주입) */
    DartClient(ConfigComponent configComponent, ObjectMapper objectMapper, RestTemplate restTemplate,
               long retryBackoffMs, long minIntervalMs) {
        this.configComponent = configComponent;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
        this.retryBackoffMs = retryBackoffMs;
        this.minIntervalMs = minIntervalMs;
    }

    /** 지금까지 호출 수 (실행마다 resetCallCount) */
    public int callCount() {
        return callCount.get();
    }

    public void resetCallCount() {
        callCount.set(0);
    }

    /**
     * @param api    예: fnlttSinglAcntAll.json, list.json
     * @param params crtfc_key 제외 파라미터
     */
    public Result get(String api, Map<String, String> params) {
        String apiKey = configComponent.getDartApiKey();
        if (apiKey == null || apiKey.isBlank()) throw new DartApiException("OPEN_DART_API_KEY 미설정");

        String last = "";
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            acquire();
            callCount.incrementAndGet();
            try {
                String raw = restTemplate.getForObject(uri(api, apiKey, params), String.class);
                Map<String, Object> body = parse(raw);
                String status = body == null ? null : String.valueOf(body.get("status"));
                if (STATUS_OK.equals(status) || STATUS_NO_DATA.equals(status)) {
                    return new Result(status, body, rows(body));
                }
                if (STATUS_LIMIT_EXCEEDED.equals(status)) throw new DartLimitExceededException("OpenDart 일일 한도 초과(020)");
                last = describe(body);
                if (!STATUS_MAINTENANCE.equals(status) && !STATUS_UNDEFINED_ERROR.equals(status)) break;
            } catch (HttpStatusCodeException e) {
                last = "HTTP " + e.getStatusCode().value();
                if (!e.getStatusCode().is5xxServerError()) break;
            } catch (ResourceAccessException e) {
                last = "네트워크 오류: " + e.getClass().getSimpleName();
            }
            log.warn("[OpenDart] {} 실패 ({}/{}): {}", api, attempt, MAX_RETRY, last);
            if (attempt < MAX_RETRY) sleep(retryBackoffMs * attempt);
        }
        throw new DartApiException("OpenDart " + api + " 실패: " + last);
    }

    /** 호출 간 최소 간격 (스레드 간 공유) */
    private synchronized void acquire() {
        long waitMs = lastCallAt + minIntervalMs - System.currentTimeMillis();
        if (waitMs > 0) sleep(waitMs);
        lastCallAt = System.currentTimeMillis();
    }

    /** 키는 쿼리에만 넣고 로그·예외 메시지에는 남기지 않는다 */
    private static URI uri(String api, String apiKey, Map<String, String> params) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(BASE_URL + api).queryParam("crtfc_key", apiKey);
        params.forEach(builder::queryParam);
        return builder.encode().build().toUri();
    }

    private Map<String, Object> parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return objectMapper.readValue(raw, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> body) {
        Object list = body.get("list");
        return list instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    private static String describe(Map<String, Object> body) {
        if (body == null) return "(응답 형식 오류)";
        return (body.getOrDefault("status", "") + " " + body.getOrDefault("message", "")).trim();
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
            throw new DartApiException("OpenDart 대기 중 인터럽트", e);
        }
    }
}
