package com.finance.dart.cointrade.exception;

/**
 * 트레이더(FastAPI) 로그 API 조회 실패 (HTTP 오류·연결 실패·응답 없음)
 */
public class TraderLogException extends RuntimeException {

    public TraderLogException(String message) {
        super(message);
    }

    public TraderLogException(String message, Throwable cause) {
        super(message, cause);
    }
}
