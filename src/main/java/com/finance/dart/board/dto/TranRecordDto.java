package com.finance.dart.board.dto;

import lombok.Data;

/**
 * 거래 기록 DTO
 */
@Data
public class TranRecordDto {

    private Long id;               // PK

    private String symbol;          // 티커
    private String companyName;     // 기업명
    private String buyDate;         // 매수일자
    private Double buyPrice;        // 매수가격(단가)
    private Integer totalBuyAmount; // 수량

    private Double currentPrice;    // 현재가
    private Double targetPrice;     // 매도목표가
    private Boolean targetPriceSync;// 목표가 동기화 (true: 계산된 목표매도가 자동 적용)
    private Boolean targetPriceCached; // 동기화 목표가 캐시 적중 여부 (false: DB의 마지막 동기화 값, 상세 모달에서 재계산)

    private Double buyExchangeRateAtTrade;  // 매수체결당시 환율

    private String rmk;             // 비고

    private String createdAt;
    private String updatedAt;

}
