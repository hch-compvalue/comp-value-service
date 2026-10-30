package com.finance.dart.common.component;

public class RedisKeyGenerator {

    /**
     * 해외기업 가치 계산 결과값
     * @param symbol    기업티커
     * @param version   계산함수버전(v1, v2, ...)
     * @return
     */
    public static String genAbroadCompValueRstData(String symbol, String version) {
        return "compvalue:abroad:calvalue:"+version+":"+symbol;
    }

    /**
     * 국내 주당가치 계산 결과
     * @return compvalue:domestic:calvalue:{version}:{stockCode}
     */
    public static String genDomesticCompValueRstData(String stockCode, String version) {
        return "compvalue:domestic:calvalue:" + version + ":" + stockCode;
    }

    /**
     * 해외기업 프로필 데이터
     * @param symbol 기업티커
     * @return compvalue:abroad:profile:{symbol}
     */
    public static String genAbroadCompanyProfile(String symbol) {
        return "compvalue:abroad:profile:" + symbol;
    }

    /**
     * 추천 종목 전체 목록 (프로파일별)
     * @param profileName 프로파일명
     * @return compvalue:abroad:recommended:{profileName}
     */
    public static String genRecommendedStocksByProfile(String profileName) {
        return "compvalue:abroad:recommended:" + profileName;
    }

    /**
     * 추천 종목 전체 목록 (Deprecated - 프로파일별로 분리됨)
     * @return compvalue:abroad:recommended:all
     * @deprecated Use {@link #genRecommendedStocksByProfile(String)} instead
     */
    @Deprecated
    public static String genRecommendedStocksAll() {
        return "compvalue:abroad:recommended:all";
    }

    /**
     * 추천 종목 개별 데이터
     * @param symbol 기업티커
     * @return compvalue:abroad:recommended:stock:{symbol}
     */
    public static String genRecommendedStock(String symbol) {
        return "compvalue:abroad:recommended:stock:" + symbol;
    }

    /**
     * 투자판단 최근 조회 내역
     * @return compvalue:evaluation:recent-queries
     */
    public static String genEvaluationRecentQueries() {
        return "compvalue:abroad:evaluation:recent-queries";
    }

}
