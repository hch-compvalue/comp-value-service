package com.finance.dart.cointrade.service;

import com.finance.dart.cointrade.consts.CoinTraderProgramConfig;
import com.finance.dart.cointrade.dto.CointradeLogContentDto;
import com.finance.dart.cointrade.dto.CointradeLogFileInfoDto;
import com.finance.dart.cointrade.dto.CointradeLogFileListDto;
import com.finance.dart.common.component.HttpClientComponent;
import com.finance.dart.common.logging.TransactionLogging;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;

/**
 * 코인 자동매매 로그 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CointradeLogService {

    private final HttpClientComponent httpClientComponent;

    @Value("${app.local}")
    private boolean isLocal;

    /**
     * 로그 파일 목록 조회
     */
    @TransactionLogging
    public CointradeLogFileListDto getLogFileList() {
        String url = buildUrl(CoinTraderProgramConfig.API_URI_LOG_FILES);

        if (log.isDebugEnabled()) {
            log.debug("코인 로그 파일 목록 조회 API 호출 - URL: {}", url);
        }

        try {
            return httpClientComponent.exchangeSync(
                    url,
                    HttpMethod.GET,
                    new ParameterizedTypeReference<CointradeLogFileListDto>() {}
            ).getBody();
        } catch (Exception e) {
            log.error("코인 로그 파일 목록 조회 실패 - error: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to get log file list", e);
        }
    }

    /**
     * 최신 로그 파일 정보 조회
     */
    public CointradeLogFileInfoDto getLatestLogFileInfo() {
        String url = buildUrl(CoinTraderProgramConfig.API_URI_LOG_LATEST);

        if (log.isDebugEnabled()) {
            log.debug("코인 최신 로그 파일 정보 조회 API 호출 - URL: {}", url);
        }

        try {
            return httpClientComponent.exchangeSync(
                    url,
                    HttpMethod.GET,
                    new ParameterizedTypeReference<CointradeLogFileInfoDto>() {}
            ).getBody();
        } catch (Exception e) {
            log.error("코인 최신 로그 파일 정보 조회 실패 - error: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to get latest log file info", e);
        }
    }

    /**
     * 로그 파일 내용 조회
     */
    public CointradeLogContentDto getLogContent(String filename) {
        String url = buildUrl(CoinTraderProgramConfig.API_URI_LOG_READ + "/" + filename);

        if (log.isDebugEnabled()) {
            log.debug("코인 로그 내용 조회 API 호출 - URL: {}, filename: {}", url, filename);
        }

        url += "?all=true";

        try {
            return httpClientComponent.exchangeSync(
                    url,
                    HttpMethod.GET,
                    new ParameterizedTypeReference<CointradeLogContentDto>() {}
            ).getBody();
        } catch (Exception e) {
            log.error("코인 로그 내용 조회 실패 - filename: {}, error: {}", filename, e.getMessage(), e);
            throw new RuntimeException("Failed to get log content", e);
        }
    }

    /**
     * 최신 로그 증분 조회 (폴링용)
     */
    public CointradeLogContentDto getLatestLogStream(Integer lastLine, Integer initialLines) {
        // 1. 최신 로그 파일 정보 먼저 조회
        CointradeLogFileInfoDto latestInfo = getLatestLogFileInfo();
        if (latestInfo == null || latestInfo.getFilename() == null) {
            throw new RuntimeException("Latest log file info not found");
        }

        String filename = latestInfo.getFilename();
        String url = buildUrl(CoinTraderProgramConfig.API_URI_LOG_INCREMENTAL + "/" +
                filename + "?last_line=" + (lastLine != null ? lastLine : 0) + "&initialLines=" + initialLines);

        if (log.isDebugEnabled()) {
            log.debug("코인 최신 로그 증분 조회 API 호출 - URL: {}, filename: {}, lastLine: {}", url, filename, lastLine);
        }

        try {
            return httpClientComponent.exchangeSync(
                    url,
                    HttpMethod.GET,
                    new ParameterizedTypeReference<CointradeLogContentDto>() {}
            ).getBody();
        } catch (Exception e) {
            log.error("코인 최신 로그 증분 조회 실패 - filename: {}, error: {}", filename, e.getMessage(), e);
            throw new RuntimeException("Failed to get latest log stream", e);
        }
    }

    private String buildUrl(String uri) {
        String baseUrl = isLocal
                ? CoinTraderProgramConfig.localHost
                : CoinTraderProgramConfig.prodHost;
        return baseUrl + "/" + uri;
    }
}
