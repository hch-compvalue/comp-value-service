package com.finance.dart.stockpredictor.consts;

/**
 * <pre>
 * 머신러닝 파드(stock-predictor) 연결 설정
 * 파이썬 (예측·재학습 API는 2026-10-16 삭제, 로그 API만 사용)
 * </pre>
 */
public class PredictionProgramConfig {

    // Server
    public static final String localHost = "http://localhost:18081";
    public static final String prodHost = "http://stock-predictor-service";


    // API

    /** 로그 API URI **/
    public static final String API_URI_logs = "logs";
    public static final String API_URI_logs_latest = "logs/latest";
    public static final String API_URI_logs_stream_latest = "logs/stream/latest";

}
