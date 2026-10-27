package com.finance.dart.api.domestic.kis;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * KIS 국내주식 조회 API 목록 (2026-10-27 Phase 1 실측으로 응답 확인한 것만)
 * - 주문 TR은 등록하지 않는다 (주문은 trader 전용)
 * - 응답 함정: 값 없음 = "99.99", 분기 값은 연초부터 누적, 연간 첫 행은 미완성 연도, 단위 억원 (docs/ROADMAP_domestic.md 0단계)
 */
@Getter
@AllArgsConstructor
public enum KisApi {

    /** 시세 · 종목 정보 **/
    PRICE("/uapi/domestic-stock/v1/quotations/inquire-price", "FHKST01010100", "주식현재가 시세 (PER·PBR·EPS·BPS·시총·상장주식수·업종)"),
    STOCK_INFO("/uapi/domestic-stock/v1/quotations/search-stock-info", "CTPF1002R", "주식기본조회 (지수업종·표준산업분류·결산월)"),
    DAILY_CHART("/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice", "FHKST03010100", "국내주식 기간별시세 (1회 최대 100행)"),
    INDEX_DAILY_CHART("/uapi/domestic-stock/v1/quotations/inquire-daily-indexchartprice", "FHKUP03500100", "업종 기간별시세 (1회 최대 50행)"),

    /** 재무 (FID_DIV_CLS_CODE 0: 연간, 1: 분기) **/
    BALANCE_SHEET("/uapi/domestic-stock/v1/finance/balance-sheet", "FHKST66430100", "대차대조표"),
    INCOME_STATEMENT("/uapi/domestic-stock/v1/finance/income-statement", "FHKST66430200", "손익계산서"),
    FINANCIAL_RATIO("/uapi/domestic-stock/v1/finance/financial-ratio", "FHKST66430300", "재무비율"),
    PROFIT_RATIO("/uapi/domestic-stock/v1/finance/profit-ratio", "FHKST66430400", "수익성비율"),
    OTHER_MAJOR_RATIOS("/uapi/domestic-stock/v1/finance/other-major-ratios", "FHKST66430500", "기타주요비율"),
    STABILITY_RATIO("/uapi/domestic-stock/v1/finance/stability-ratio", "FHKST66430600", "안정성비율"),
    GROWTH_RATIO("/uapi/domestic-stock/v1/finance/growth-ratio", "FHKST66430800", "성장성비율"),

    /** 기타 **/
    INVEST_OPINION("/uapi/domestic-stock/v1/quotations/invest-opinion", "FHKST663300C0", "증권사별 투자의견·목표가"),
    NEWS_TITLE("/uapi/domestic-stock/v1/quotations/news-title", "FHKST01011800", "뉴스 제목 (종목 필터 느슨 → iscd 로 후처리)"),
    DIVIDEND("/uapi/domestic-stock/v1/ksdinfo/dividend", "HHKDB669102C0", "예탁원 배당일정");

    private final String path;
    private final String trId;
    private final String description;
}
