package com.finance.dart.monitoring.service;

import com.finance.dart.common.component.ConfigComponent;
import com.finance.dart.monitoring.dto.ServiceStatusDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 외부 API 헬스체크 서비스
 * - FMP, DART, Upbit, OpenAI 각각 경량 요청으로 UP/DOWN + 응답시간 측정
 * - 30초 주기로 호출 (rate limit 부담 최소화)
 * - RestTemplate 사용 (실제 API 호출과 동일한 HTTP 클라이언트)
 */
@Slf4j
@Service
public class ExternalApiHealthService {

    private final RestTemplate healthCheckRestTemplate;
    private final ConfigComponent configComponent;

    /** 캐시된 결과 — 30초 주기로 갱신 */
    private volatile List<ServiceStatusDto> cachedStatus = List.of();

    public ExternalApiHealthService(ConfigComponent configComponent) {
        // 헬스체크 전용 RestTemplate (connectTimeout=5s, readTimeout=10s)
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        this.healthCheckRestTemplate = new RestTemplate(factory);
        this.configComponent = configComponent;
    }

    public List<ServiceStatusDto> getCachedStatus() {
        return cachedStatus;
    }

    /**
     * 전체 외부 API 헬스체크 실행 및 캐시 갱신
     */
    public void refreshAll() {
        List<ServiceStatusDto> results = new ArrayList<>();
        results.add(checkFmp());
        results.add(checkDart());
        results.add(checkUpbit());
        results.add(checkOpenAi());
        cachedStatus = results;
    }

    private ServiceStatusDto checkFmp() {
        String apiKey = configComponent.getFmpApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return ServiceStatusDto.builder().name("FMP").status("DOWN").version("No API Key").build();
        }
        return timedCheck("FMP",
                "https://financialmodelingprep.com/stable/profile?symbol=AAPL&apikey=" + apiKey);
    }

    private ServiceStatusDto checkDart() {
        String apiKey = configComponent.getDartApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return ServiceStatusDto.builder().name("DART").status("DOWN").version("No API Key").build();
        }
        // company.json: 단일 기업 정보 조회 (경량 JSON 응답)
        return timedCheck("DART",
                "https://opendart.fss.or.kr/api/company.json?crtfc_key=" + apiKey + "&corp_code=00126380");
    }

    private ServiceStatusDto checkUpbit() {
        return timedCheck("Upbit", "https://api.upbit.com/v1/market/all");
    }

    private ServiceStatusDto checkOpenAi() {
        String apiKey = configComponent.getOpenAiApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return ServiceStatusDto.builder().name("OpenAI").status("DOWN").version("No API Key").build();
        }
        return timedCheckWithHeaders("OpenAI",
                "https://api.openai.com/v1/models",
                "Authorization", "Bearer " + apiKey);
    }

    /**
     * 공통: URL에 GET 요청 보내서 응답 시간 측정 (RestTemplate)
     */
    private ServiceStatusDto timedCheck(String name, String url) {
        long start = System.currentTimeMillis();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = healthCheckRestTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            long elapsed = System.currentTimeMillis() - start;
            if (response.getStatusCode().is2xxSuccessful()) {
                return ServiceStatusDto.builder().name(name).status("UP")
                        .uptime(elapsed + "ms").build();
            }
        } catch (Exception e) {
            log.warn("{} 헬스체크 실패: [{}] {}", name, e.getClass().getSimpleName(), e.getMessage());
        }
        long elapsed = System.currentTimeMillis() - start;
        return ServiceStatusDto.builder().name(name).status("DOWN")
                .uptime(elapsed + "ms").build();
    }

    /**
     * 커스텀 헤더가 필요한 API 헬스체크 (OpenAI Authorization 등)
     */
    private ServiceStatusDto timedCheckWithHeaders(String name, String url,
                                                   String headerName, String headerValue) {
        long start = System.currentTimeMillis();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
            headers.set(headerName, headerValue);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = healthCheckRestTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            long elapsed = System.currentTimeMillis() - start;
            if (response.getStatusCode().is2xxSuccessful()) {
                return ServiceStatusDto.builder().name(name).status("UP")
                        .uptime(elapsed + "ms").build();
            }
        } catch (Exception e) {
            log.warn("{} 헬스체크 실패: [{}] {}", name, e.getClass().getSimpleName(), e.getMessage());
        }
        long elapsed = System.currentTimeMillis() - start;
        return ServiceStatusDto.builder().name(name).status("DOWN")
                .uptime(elapsed + "ms").build();
    }
}
