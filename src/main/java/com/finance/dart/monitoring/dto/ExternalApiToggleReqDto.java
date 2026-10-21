package com.finance.dart.monitoring.dto;

import lombok.Data;

/** 외부 API 모니터링 사용 여부 변경 요청 */
@Data
public class ExternalApiToggleReqDto {
    private Boolean enabled;
}
