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
    public static final String prodHost = "http://cointrader-service";


    // API

    /** [v5.0 전략] 상태 조회 **/
    public static final String API_URI_STRATEGY_STATUS = "api/strategy/status";

    /** [v5.0 전략] 실행 기록 **/
    public static final String API_URI_STRATEGY_RUNS = "api/strategy/runs";

    /** [v5.0 전략] 설정 변경 (모드/투자금/실행시각) **/
    public static final String API_URI_STRATEGY_SETTINGS = "api/strategy/settings";

    /** [v5.0 전략] 즉시 실행 **/
    public static final String API_URI_STRATEGY_RUN = "api/strategy/run";

    /** [v5.0 전략] 긴급 전량 매도 + 중지 **/
    public static final String API_URI_STRATEGY_EMERGENCY_EXIT = "api/strategy/emergency-exit";

    /** 매수 프로세스 시작 시간 조회 API URI **/
    public static final String API_URI_BUY_NEXT_RUN = "api/scheduler/next-run";

    /** 로그 목록 조회 API URI **/
    public static final String API_URI_LOG_FILES = "api/logs/files";

    /** 최신 로그 정보 조회 API URI **/
    public static final String API_URI_LOG_LATEST = "api/logs/latest";

    /** 특정 로그 파일 읽기 API URI (파일명을 뒤에 붙여서 사용) **/
    public static final String API_URI_LOG_READ = "api/logs/read";

    /** 증분 로그 조회 API URI (파일명을 뒤에 붙여서 사용) **/
    public static final String API_URI_LOG_INCREMENTAL = "api/logs/incremental";

    /** 매수 프로세스 수동 실행 **/
    public static final String API_URI_TRADE_BUY_START = "api/trade/buy/start";

    /** 매도 프로세스 수동 실행 **/
    public static final String API_URI_TRADE_SELL_START = "api/trade/sell/start";

    /** 매수/매도 프로세스 수동 중지 **/
    public static final String API_URI_TRADE_STOP = "api/trade/stop";

    /** 모델 학습 수동 실행 **/
    public static final String API_URI_MODEL_TRAIN = "api/model/train";

    /** 스케줄러 설정 즉시 리로드 **/
    public static final String API_URI_SCHEDULER_RELOAD = "api/scheduler/reload";

    /** 프로세스 진행율 상태 조회 **/
    public static final String API_URI_PROCESS_STATUS_BUY = "api/process/status/buy";

    /** 매도 프로세스 진행율 상태 조회 **/
    public static final String API_URI_PROCESS_STATUS_SELL = "api/process/status/sell";

    /** 백테스트 실행 **/
    public static final String API_URI_BACKTEST_RUN = "api/backtest/run";

    /** 백테스트 작업 상태 조회 **/
    public static final String API_URI_BACKTEST_STATUS = "api/backtest/status";

    /** 백테스트 결과 조회 **/
    public static final String API_URI_BACKTEST_RESULT = "api/backtest/result";

    /** 백테스트 이력 조회 **/
    public static final String API_URI_BACKTEST_HISTORY = "api/backtest/history";

    /** 백테스트 작업 취소 **/
    public static final String API_URI_BACKTEST_CANCEL = "api/backtest/cancel";

    /** 백테스트 제목 수정 **/
    public static final String API_URI_BACKTEST_UPDATE = "api/backtest/result";

    /** 업비트 KRW 잔액 조회 **/
    public static final String API_URI_ACCOUNT_BALANCE = "api/account/balance";

    /** 업비트 전체 계좌 조회 (KRW + 모든 보유 코인) **/
    public static final String API_URI_ACCOUNT_BALANCES = "api/account/balances";

    /** 보유 종목 매도 **/
    public static final String API_URI_HOLDINGS_SELL = "api/holdings/sell";

    /** 먼지(dust) 잔량 조회 **/
    public static final String API_URI_DUST_LIST = "api/dust";

    /** 먼지 정리완료 처리 (경로 포맷에 id 삽입) **/
    public static final String API_URI_DUST_RESOLVE_FORMAT = "api/dust/%d/resolve";

    /** 스캐너 시그널 목록 조회 **/
    public static final String API_URI_SCANNER_SIGNALS = "api/scanner/signals";

    /** 스캐너 상태 조회 **/
    public static final String API_URI_SCANNER_STATUS = "api/scanner/status";

    /** 모의투자 보유 종목 조회 **/
    public static final String API_URI_PAPER_HOLDINGS = "api/paper/holdings";

    /** 모의투자 거래 기록 조회 **/
    public static final String API_URI_PAPER_HISTORY = "api/paper/history";

    /** 모의투자 데이터 초기화 **/
    public static final String API_URI_PAPER_RESET = "api/paper/reset";

    /** 통합 모델 상태 조회 **/
    public static final String API_URI_MODEL_STATUS = "api/model-status";

    /** 재학습 진행률 상태 조회 **/
    public static final String API_URI_RETRAIN_STATUS = "api/retrain/status";

}
