package com.finance.dart.cointrade.consts;

/**
 * <pre>
 * 머신러닝 코인자동매매 프로그램 설정
 * 파이썬
 * </pre>
 */
public class CoinTraderProgramConfig {

    // Server
    public static final String localHost = "http://localhost:18082";
    public static final String prodHost = "http://trader-service";


    // API

    /** 전략 ID 미지정 시 기본값 (일반화 전 화면 호환: 코인 BTC 추세) **/
    public static final String DEFAULT_STRATEGY_ID = "btc_trend";

    /** 전략 ID 허용 형식 (URL 쿼리에 그대로 붙이므로 검증) **/
    public static final String STRATEGY_ID_PATTERN = "^[a-z0-9_]{1,30}$";

    /** [v5.1 전략] 등록 전략(프리셋) 목록 **/
    public static final String API_URI_STRATEGY_LIST = "api/strategy/list";

    /** [v5.0 전략] 상태 조회 **/
    public static final String API_URI_STRATEGY_STATUS = "api/strategy/status";

    /** [v5.0 전략] 실행 기록 **/
    public static final String API_URI_STRATEGY_RUNS = "api/strategy/runs";

    /** [v5.0 전략] 설정 변경 (모드/투자금/실행시각/일시 중지 종목) **/
    public static final String API_URI_STRATEGY_SETTINGS = "api/strategy/settings";

    /** [v5.0 전략] 즉시 실행 **/
    public static final String API_URI_STRATEGY_RUN = "api/strategy/run";

    /** [v5.0 전략] 긴급 전량 매도 + 중지 **/
    public static final String API_URI_STRATEGY_EMERGENCY_EXIT = "api/strategy/emergency-exit";

    /** 로그 목록 조회 API URI **/
    public static final String API_URI_LOG_FILES = "api/logs/files";

    /** 최신 로그 정보 조회 API URI **/
    public static final String API_URI_LOG_LATEST = "api/logs/latest";

    /** 특정 로그 파일 읽기 API URI (파일명을 뒤에 붙여서 사용) **/
    public static final String API_URI_LOG_READ = "api/logs/read";

    /** 증분 로그 조회 API URI (파일명을 뒤에 붙여서 사용) **/
    public static final String API_URI_LOG_INCREMENTAL = "api/logs/incremental";

}
