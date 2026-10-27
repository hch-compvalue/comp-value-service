package com.finance.dart.api.domestic.collect;

/**
 * 국내 데이터 수집 실패 (다운로드·파일 형식·데이터 이상)
 */
public class KrCollectException extends RuntimeException {

    public KrCollectException(String message) {
        super(message);
    }

    public KrCollectException(String message, Throwable cause) {
        super(message, cause);
    }
}
