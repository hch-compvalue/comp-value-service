package com.finance.dart.api.domestic.collect;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * zip 파일 다운로드 (KIS 종목 마스터, OpenDart 고유번호)
 * - 타임아웃: 연결 5초 / 응답 30초, 재시도 최대 3회
 * - OpenDart는 키 오류 등도 HTTP 200 + XML 본문으로 응답 → zip 시그니처(PK)가 아니면 실패로 본다
 */
@Slf4j
@Component
public class KrZipDownloader {

    static final int MAX_RETRY = 3;
    private static final long RETRY_BACKOFF_MS = 2_000;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final byte ZIP_MAGIC_0 = 'P';
    private static final byte ZIP_MAGIC_1 = 'K';

    private final RestTemplate restTemplate;
    private final long retryBackoffMs;

    public KrZipDownloader() {
        this(defaultRestTemplate(), RETRY_BACKOFF_MS);
    }

    /** 테스트용 (RestTemplate·대기시간 주입) */
    KrZipDownloader(RestTemplate restTemplate, long retryBackoffMs) {
        this.restTemplate = restTemplate;
        this.retryBackoffMs = retryBackoffMs;
    }

    /**
     * @param url   다운로드 주소 (키가 들어 있을 수 있어 로그에는 label만 남긴다)
     * @param label 로그용 이름
     */
    public byte[] download(String url, String label) {
        String last = "";
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                byte[] body = restTemplate.getForObject(url, byte[].class);
                if (isZip(body)) return body;
                last = body == null ? "빈 응답" : "zip 아님(" + body.length + "바이트)";
            } catch (RestClientException e) {
                last = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            log.warn("[국내 수집] {} 다운로드 실패 ({}/{}): {}", label, attempt, MAX_RETRY, last);
            if (attempt < MAX_RETRY) sleep(retryBackoffMs * attempt);
        }
        throw new KrCollectException(label + " 다운로드 실패: " + last);
    }

    private static boolean isZip(byte[] body) {
        return body != null && body.length > 1 && body[0] == ZIP_MAGIC_0 && body[1] == ZIP_MAGIC_1;
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
            throw new KrCollectException("다운로드 재시도 대기 중 인터럽트", e);
        }
    }
}
