package com.finance.dart.board.dto;

import lombok.Data;

/**
 * 보유종목 종목 메모 DTO
 */
@Data
public class SymbolMemoDto {

    private String symbol;          // 티커
    private String memo;            // 종목 메모 (빈 값 저장 = 삭제)
    private String updatedAt;
}
