package com.finance.dart.api.domestic.dto;

import lombok.Data;

/**
 * 국내 기업정보 — 미국 기업정보(FMP profile)와 같은 필드명 (화면 공용, 저장된 마스터·일봉으로만 구성)
 */
@Data
public class KrStockProfile {
    private String symbol;
    private String companyName;
    private String exchange;
    private String exchangeFullName;
    private String currency;
    private String country;
    private Double price;               // 전 거래일 종가
    private Double change;              // 전일 대비
    private Double changePercentage;
    private Long marketCap;             // 종가 × 상장주식수
    private String range;               // 52주 최저-최고
    private Long averageVolume;
    private String sector;              // KSIC 업종명
    private String industry;
    private String ipoDate;             // 상장일
    private Long listedShares;
    private String fiscalMonth;         // 결산월
    private String administrative;      // 관리종목 Y/N
    private String tradingHalt;         // 거래정지 Y/N
    private String priceDate;           // 가격 기준일
}
