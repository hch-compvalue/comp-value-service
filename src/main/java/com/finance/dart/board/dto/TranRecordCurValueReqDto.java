package com.finance.dart.board.dto;

import com.finance.dart.api.common.constants.Market;
import lombok.Data;

import java.util.List;

/**
 * 현재가 갱신 요청 DTO
 */
@Data
public class TranRecordCurValueReqDto {

    private List<String> symbols; // 티커
    private Market market;        // 시장 (없으면 US)

}
