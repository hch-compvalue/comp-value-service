package com.finance.dart.api.domestic.kis;

/**
 * KIS 조회 실패 (재시도 후에도 실패, 키·토큰 미설정 등)
 */
public class KisApiException extends RuntimeException {

    public KisApiException(String message) {
        super(message);
    }

    public KisApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
