package com.finance.dart.api.common.dto;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

/**
 * 한주당 가치계산 DTO
 */
@Data
public class CompanySharePriceCalculator {

    /**
     * 단위
     */
    @SerializedName("unit")
    private String unit;

    // 영업이익 ====================================
    /**
     * 전전기 영업이익
     */
    @SerializedName("operatingProfitPrePre")
    private String operatingProfitPrePre;
    /**
     * 전기 영업이익
     */
    @SerializedName("operatingProfitPre")
    private String operatingProfitPre;
    /**
     * 당기 영업이익
     */
    @SerializedName("operatingProfitCurrent")
    private String operatingProfitCurrent;

    // 매출액 (V3 추가) ====================================
    /**
     * 매출액 (3년 평균 또는 당기)
     */
    @SerializedName("revenue")
    private String revenue;
    /**
     * 매출 성장률
     */
    @SerializedName("revenueGrowth")
    private String revenueGrowth;
    /**
     * PSR (Price to Sales Ratio)
     */
    @SerializedName("psr")
    private String psr;


    /**
     * 유동자산합계
     */
    @SerializedName("currentAssetsTotal")
    private String currentAssetsTotal;

    /**
     * 유동부채합계
     */
    @SerializedName("currentLiabilitiesTotal")
    private String currentLiabilitiesTotal;

    /**
     * 유동비율
     */
    @SerializedName("currentRatio")
    private String currentRatio;

    /**
     * 투자자산(비유동자산내)
     */
    @SerializedName("investmentAssets")
    private String investmentAssets;

    /**
     * 고정부채(비유동부채)
     */
    @SerializedName("fixedLiabilities")
    private String fixedLiabilities;

    /**
     * 발행주식수
     */
    @SerializedName("issuedShares")
    private String issuedShares;


    /**
     * PER
     */
    @SerializedName("per")
    private String per;

    /**
     * <pre>
     * EPS 성장률
     * V2 추가
     * </pre>
     */
    @SerializedName("epsgrowth")
    private String epsgrowth;

    /**
     * <pre>
     * 영업이익 성장률
     * v2 추가
     * </pre>
     */
    @SerializedName("growth")
    private String operatingIncomeGrowth;

    /**
     * <pre>
     * 무형자산
     * v2 추가
     * </pre>
     */
    @SerializedName("intangibleAssets")
    private String intangibleAssets;

    /**
     * <pre>
     * 당기 R&D
     * v2 추가
     * </pre>
     */
    @SerializedName("rndCurrent")
    private String rndCurrent;

    /**
     * <pre>
     * 전기 R&D
     * v2 추가
     * </pre>
     */
    @SerializedName("rndPre")
    private String rndPre;

    /**
     * <pre>
     * 전전기 R&D
     * v2 추가
     * </pre>
     */
    @SerializedName("rndPrePre")
    private String rndPrePre;

    /**
     * <pre>
     * 총부채
     * v2 추가
     * </pre>
     */
    @SerializedName("totalDebt")
    private String totalDebt;

    /**
     * <pre>
     * 현금성자산
     * v2 추가
     * </pre>
     */
    @SerializedName("cashAndCashEquivalents")
    private String cashAndCashEquivalents;

    // 분기 영업이익 (V6 추가) ====================================
    /**
     * 최근 1분기 영업이익
     */
    @SerializedName("quarterlyOpIncomeQ1")
    private String quarterlyOpIncomeQ1;
    /**
     * 최근 2분기 영업이익
     */
    @SerializedName("quarterlyOpIncomeQ2")
    private String quarterlyOpIncomeQ2;
    /**
     * 최근 3분기 영업이익
     */
    @SerializedName("quarterlyOpIncomeQ3")
    private String quarterlyOpIncomeQ3;
    /**
     * 최근 4분기 영업이익
     */
    @SerializedName("quarterlyOpIncomeQ4")
    private String quarterlyOpIncomeQ4;

    // ---------------------- 필수값 아님

    /**
     * 현재 주가
     */
    @SerializedName("price")
    private double price = 0;

    /**
     * 기업 CIK (해외기업)
     */
    @SerializedName("cik")
    private String cik;

    // PBR 기반 평가용 (Financial Services) ====================================
    /**
     * 주당 장부가치 (Book Value Per Share)
     */
    @SerializedName("bookValuePerShare")
    private String bookValuePerShare;

    /**
     * 자기자본이익률 (Return on Equity)
     */
    @SerializedName("roe")
    private String roe;

    /**
     * 베타 (시장 변동성 대비 개별 종목 변동성)
     */
    @SerializedName("beta")
    private String beta;

    /**
     * 산업군 (V8 Financial Services 서브섹터 분화용)
     */
    @SerializedName("industry")
    private String industry;

    // V9 자기자본법용 ====================================
    /**
     * 이자비용 (최근 연간, V9 세후 이익 산출용)
     */
    @SerializedName("interestExpense")
    private String interestExpense;

    /**
     * 유효세율 (최근 연간 법인세/세전이익, V9). 비어 있으면 기본세율 사용
     */
    @SerializedName("effectiveTaxRate")
    private String effectiveTaxRate;

}
