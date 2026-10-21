package com.finance.dart.monitoring.service;

import com.finance.dart.common.component.ConfigComponent;
import com.finance.dart.common.exception.BizException;
import com.finance.dart.monitoring.entity.ExternalApiSettingEntity;
import com.finance.dart.monitoring.repository.ExternalApiSettingRepository;
import com.finance.dart.monitoring.dto.ServiceStatusDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 외부 API 헬스체크 서비스
 * - FMP, DART, Upbit, OpenAI 각각 경량 요청으로 UP/DOWN + 응답시간 측정
 * - 30초 주기로 호출 (rate limit 부담 최소화)
 * - RestTemplate 사용 (실제 API 호출과 동일한 HTTP 클라이언트)
 * - 상태: UP / DOWN / NOT_CONFIGURED(API 키 없음) / DISABLED(대시보드에서 모니터링 끔 — 호출 안 함)
 */
@Slf4j
@Service
public class ExternalApiHealthService {

    public static final String FMP = "FMP";
    public static final String DART = "DART";
    public static final String UPBIT = "Upbit";
    public static final String OPENAI = "OpenAI";
    /** 확인 대상 (화면 표시 순서) */
    public static final List<String> API_NAMES = List.of(FMP, DART, UPBIT, OPENAI);

    public static final String STATUS_UP = "UP";
    public static final String STATUS_DOWN = "DOWN";
    public static final String STATUS_NOT_CONFIGURED = "NOT_CONFIGURED";
    public static final String STATUS_DISABLED = "DISABLED";
    private static final String NO_API_KEY = "No API Key";
    private static final String FLAG_ON = "Y";
    private static final String FLAG_OFF = "N";

    private final RestTemplate healthCheckRestTemplate;
    private final ConfigComponent configComponent;
    private final ExternalApiSettingRepository settingRepository;

    /** 캐시된 결과 — 30초 주기로 갱신 */
    private volatile List<ServiceStatusDto> cachedStatus = List.of();

    public ExternalApiHealthService(ConfigComponent configComponent, ExternalApiSettingRepository settingRepository) {
        // 헬스체크 전용 RestTemplate (connectTimeout=5s, readTimeout=10s)
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        this.healthCheckRestTemplate = new RestTemplate(factory);
        this.configComponent = configComponent;
        this.settingRepository = settingRepository;
    }

    public List<ServiceStatusDto> getCachedStatus() {
        return cachedStatus;
    }

    /**
     * 전체 외부 API 헬스체크 실행 및 캐시 갱신
     */
    public void refreshAll() {
        Set<String> disabled = loadDisabledNames();
        cachedStatus = API_NAMES.stream().map(name -> check(name, disabled)).toList();
    }

    /**
     * 모니터링 사용 여부 변경 → 저장 후 그 API 상태만 바로 다시 계산
     */
    public ServiceStatusDto setEnabled(String apiName, boolean enabled) {
        if (!API_NAMES.contains(apiName)) {
            throw new BizException("알 수 없는 외부 API 입니다: " + apiName);
        }
        ExternalApiSettingEntity setting = settingRepository.findById(apiName).orElseGet(() -> {
            ExternalApiSettingEntity e = new ExternalApiSettingEntity();
            e.setApiName(apiName);
            return e;
        });
        setting.setEnabled(enabled ? FLAG_ON : FLAG_OFF);
        settingRepository.save(setting);

        ServiceStatusDto updated = check(apiName, enabled ? Set.of() : Set.of(apiName));
        List<ServiceStatusDto> next = new ArrayList<>(cachedStatus);
        next.removeIf(s -> apiName.equals(s.getName()));
        next.add(updated);
        next.sort((a, b) -> API_NAMES.indexOf(a.getName()) - API_NAMES.indexOf(b.getName()));
        cachedStatus = List.copyOf(next);
        log.info("외부 API 모니터링 {} : {}", enabled ? "사용" : "사용 안 함", apiName);
        return updated;
    }

    /** 대시보드에서 끈 API 이름 (DB 조회 실패 시 전부 사용으로 봄) */
    private Set<String> loadDisabledNames() {
        try {
            return settingRepository.findAll().stream()
                    .filter(s -> FLAG_OFF.equals(s.getEnabled()))
                    .map(ExternalApiSettingEntity::getApiName)
                    .collect(Collectors.toSet());
        } catch (DataAccessException e) {
            log.warn("외부 API 설정 조회 실패 — 전부 사용으로 확인: {}", e.getMessage());
            return Set.of();
        }
    }

    private ServiceStatusDto check(String name, Set<String> disabled) {
        if (disabled.contains(name)) {
            return ServiceStatusDto.builder().name(name).status(STATUS_DISABLED).build();
        }
        return switch (name) {
            case FMP -> checkFmp();
            case DART -> checkDart();
            case UPBIT -> checkUpbit();
            case OPENAI -> checkOpenAi();
            default -> ServiceStatusDto.builder().name(name).status(STATUS_DOWN).build();
        };
    }

    /** API 키 없음 — 호출하지 않고 미설정으로 표시 (오류 아님) */
    private ServiceStatusDto notConfigured(String name) {
        return ServiceStatusDto.builder().name(name).status(STATUS_NOT_CONFIGURED).version(NO_API_KEY).build();
    }

    private ServiceStatusDto checkFmp() {
        String apiKey = configComponent.getFmpApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return notConfigured(FMP);
        }
        return timedCheck(FMP,
                "https://financialmodelingprep.com/stable/profile?symbol=AAPL&apikey=" + apiKey);
    }

    private ServiceStatusDto checkDart() {
        String apiKey = configComponent.getDartApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return notConfigured(DART);
        }
        // company.json: 단일 기업 정보 조회 (경량 JSON 응답)
        return timedCheck(DART,
                "https://opendart.fss.or.kr/api/company.json?crtfc_key=" + apiKey + "&corp_code=00126380");
    }

    private ServiceStatusDto checkUpbit() {
        return timedCheck(UPBIT, "https://api.upbit.com/v1/market/all");
    }

    private ServiceStatusDto checkOpenAi() {
        String apiKey = configComponent.getOpenAiApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return notConfigured(OPENAI);
        }
        return timedCheckWithHeaders(OPENAI,
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
                return ServiceStatusDto.builder().name(name).status(STATUS_UP)
                        .uptime(elapsed + "ms").build();
            }
        } catch (Exception e) {
            log.warn("{} 헬스체크 실패: [{}] {}", name, e.getClass().getSimpleName(), e.getMessage());
        }
        long elapsed = System.currentTimeMillis() - start;
        return ServiceStatusDto.builder().name(name).status(STATUS_DOWN)
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
                return ServiceStatusDto.builder().name(name).status(STATUS_UP)
                        .uptime(elapsed + "ms").build();
            }
        } catch (Exception e) {
            log.warn("{} 헬스체크 실패: [{}] {}", name, e.getClass().getSimpleName(), e.getMessage());
        }
        long elapsed = System.currentTimeMillis() - start;
        return ServiceStatusDto.builder().name(name).status(STATUS_DOWN)
                .uptime(elapsed + "ms").build();
    }
}
