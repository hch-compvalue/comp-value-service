package com.finance.dart.stockpredictor.service;

import com.finance.dart.stockpredictor.consts.PredictionProgramConfig;
import com.finance.dart.stockpredictor.dto.LogContentDto;
import com.finance.dart.stockpredictor.dto.LogFileInfoDto;
import com.finance.dart.stockpredictor.dto.LogFileListDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;

/**
 * 머신러닝 서비스 (stock-predictor 파드 로그 조회 프록시)
 * 기존 주가 예측·재학습 기능은 2026-10-16 삭제됨 (아웃오브샘플 성능이 단순 기준선 이하)
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class MlService {

    @Value("${app.local}")
    private boolean isLocal;

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;

    /**
     * ML 파드 호출용 RestTemplate (로그 조회만 하므로 짧은 타임아웃)
     */
    private RestTemplate createMlRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        return new RestTemplate(factory);
    }

    /**
     * 로그 파일 목록 조회
     *
     * @return 로그 파일 목록
     */
    public LogFileListDto getLogFileList() {

        String baseUrl = isLocal
                ? PredictionProgramConfig.localHost
                : PredictionProgramConfig.prodHost;

        String url = String.format("%s/%s", baseUrl, PredictionProgramConfig.API_URI_logs);

        if (log.isDebugEnabled()) {
            log.debug("로그 파일 목록 조회 API 호출 - URL: {}", url);
        }

        try {
            RestTemplate restTemplate = createMlRestTemplate();
            URI uri = URI.create(url);
            ResponseEntity<LogFileListDto> response = restTemplate.exchange(
                    uri,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<LogFileListDto>() {}
            );

            LogFileListDto body = response.getBody();

            if (log.isDebugEnabled()) {
                log.debug("로그 파일 목록 조회 완료 - response: {}", body);
            }

            return body;

        } catch (Exception e) {
            log.error("로그 파일 목록 조회 실패 - error: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to get log file list", e);
        }
    }

    /**
     * 최신 로그 파일 정보 조회
     *
     * @return 최신 로그 파일 정보
     */
    public LogFileInfoDto getLatestLogFileInfo() {

        String baseUrl = isLocal
                ? PredictionProgramConfig.localHost
                : PredictionProgramConfig.prodHost;

        String url = String.format("%s/%s", baseUrl, PredictionProgramConfig.API_URI_logs_latest);

        if (log.isDebugEnabled()) {
            log.debug("최신 로그 파일 정보 조회 API 호출 - URL: {}", url);
        }

        try {
            RestTemplate restTemplate = createMlRestTemplate();
            URI uri = URI.create(url);
            ResponseEntity<LogFileInfoDto> response = restTemplate.exchange(
                    uri,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<LogFileInfoDto>() {}
            );

            LogFileInfoDto body = response.getBody();

            if (log.isDebugEnabled()) {
                log.debug("최신 로그 파일 정보 조회 완료 - response: {}", body);
            }

            return body;

        } catch (Exception e) {
            log.error("최신 로그 파일 정보 조회 실패 - error: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to get latest log file info", e);
        }
    }

    /**
     * 로그 파일 내용 조회
     *
     * @param filename 로그 파일명
     * @return 로그 내용
     */
    public LogContentDto getLogContent(String filename) {

        String baseUrl = isLocal
                ? PredictionProgramConfig.localHost
                : PredictionProgramConfig.prodHost;

        String url = String.format("%s/%s/%s", baseUrl, PredictionProgramConfig.API_URI_logs, filename);

        //2 파라미터 정의
        url = url + "?all=true";    // all : 로그 전체 조회

        if (log.isDebugEnabled()) {
            log.debug("로그 내용 조회 API 호출 - URL: {}, filename: {}", url, filename);
        }

        try {
            RestTemplate restTemplate = createMlRestTemplate();
            URI uri = URI.create(url);
            ResponseEntity<LogContentDto> response = restTemplate.exchange(
                    uri,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<LogContentDto>() {}
            );

            LogContentDto body = response.getBody();

            if (log.isDebugEnabled()) {
                log.debug("로그 내용 조회 완료 - filename: {}", filename);
            }

            return body;

        } catch (Exception e) {
            log.error("로그 내용 조회 실패 - filename: {}, error: {}", filename, e.getMessage(), e);
            throw new RuntimeException("Failed to get log content", e);
        }
    }

    /**
     * 최신 로그 증분 조회 (폴링용)
     *
     * @param lastLine 마지막으로 읽은 줄 번호 (0이면 초기 로드)
     * @return 최신 로그 내용
     */
    public LogContentDto getLatestLogStream(Integer lastLine) {

        String baseUrl = isLocal
                ? PredictionProgramConfig.localHost
                : PredictionProgramConfig.prodHost;

        String url = String.format("%s/%s?lastLine=%d",
                baseUrl,
                PredictionProgramConfig.API_URI_logs_stream_latest,
                lastLine != null ? lastLine : 0);

        if (log.isDebugEnabled()) {
            log.debug("최신 로그 조회 API 호출 - URL: {}, lastLine: {}", url, lastLine);
        }

        try {
            RestTemplate restTemplate = createMlRestTemplate();
            URI uri = URI.create(url);
            ResponseEntity<LogContentDto> response = restTemplate.exchange(
                    uri,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<LogContentDto>() {}
            );

            LogContentDto body = response.getBody();

            if (log.isDebugEnabled()) {
                log.debug("최신 로그 조회 완료 - endLine: {}, totalLines: {}",
                        body != null ? body.getEndLine() : null,
                        body != null ? body.getTotalLines() : null);
            }

            return body;

        } catch (Exception e) {
            log.error("최신 로그 조회 실패 - lastLine: {}, error: {}", lastLine, e.getMessage(), e);
            throw new RuntimeException("Failed to get latest log stream", e);
        }
    }
}
