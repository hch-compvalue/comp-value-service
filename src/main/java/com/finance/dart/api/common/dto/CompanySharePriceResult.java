package com.finance.dart.api.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 1주당 가치 계산 결과
 */
@Data
@NoArgsConstructor
public class CompanySharePriceResult {

    private boolean 정상처리여부 = true;
    private String 결과메시지 = "";

    private String 기업코드 = "";
    private String 기업심볼 = "";
    private String 기업명 = "";

    private String 주식코드 = "";

    private String 주당가치 = "";         // 1주당 가치 (조정된 적정가)
    private String 계산된주당가치 = "";    // 계산된 원본 적정가
    private String 현재가격 = "";         // 현재주식가격
    private String 확인시간 = "";         // yyyyMMdd HH:mm:

    private String 버전 = "";
    private String 섹터 = "";             // 섹터명 (V4 이후 추가됨)

    private String 매수적정가 = "";         // 안전마진 적용 매수가 (V7~)
    private String 목표매도가 = "";         // 적정가 = 매도 목표가 (V7~)
    private String 손절매가 = "";           // 하드 스탑 가격 (V8~)
    private String 안전마진율 = "";         // 실제 적용된 안전마진 비율 (V8~)

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private CompanySharePriceResultDetail 상세정보;

    public CompanySharePriceResult(String responseMessage) {
        this.결과메시지 = responseMessage;
    }
}
