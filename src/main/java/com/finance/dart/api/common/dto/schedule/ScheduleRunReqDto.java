package com.finance.dart.api.common.dto.schedule;

import lombok.Data;

/**
 * 스케줄 수동 실행 요청
 */
@Data
public class ScheduleRunReqDto {
    private Integer maxCount;           // 처리 건수 제한 (0 또는 null = 전체)
}
