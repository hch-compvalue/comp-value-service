package com.finance.dart.api.domestic.dart;

/**
 * OpenDart 일일 한도 초과(status 020) 또는 이번 실행 호출 예산 소진 — 그날 수집 중단, 다음 실행이 이어 받음
 */
public class DartLimitExceededException extends DartApiException {

    public DartLimitExceededException(String message) {
        super(message);
    }
}
