package com.finance.dart.board.dto;

import com.finance.dart.api.common.constants.Market;
import lombok.Data;

/**
 * 매도 기록 DTO
 */
@Data
public class SellRecordDto {

    private Long id;                    // 매도기록ID
    // --- 거래 기록 정보
    private String symbol;              // 티커
    private Market market;              // 시장 (US/KR — 등록 시 지정, 없으면 US)
    private String companyName;         // 기업명

    // --- 매도 정보
    private String sellDate;            // 매도일자
    private Double sellPrice;           // 매도가격(단가)
    private Integer sellQty;            // 매도수량
    private Double realizedPnl;         // 실현손익

    private Double buyExchangeRateAtTrade;  // 매수체결당시 환율
    private Double sellExchangeRateAtTrade; // 매도체결당시 환율

    private String rmk;                 // 비고

    private String createdAt;           // 등록일자
    private String updatedAt;           // 수정일자
}
