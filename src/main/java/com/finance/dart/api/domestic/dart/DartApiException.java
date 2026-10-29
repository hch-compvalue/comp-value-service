package com.finance.dart.api.domestic.dart;

/**
 * OpenDart 호출 실패 (재시도 후에도 실패, 키 오류 등)
 */
public class DartApiException extends RuntimeException {

    public DartApiException(String message) {
        super(message);
    }

    public DartApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
