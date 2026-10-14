package com.finance.dart.api.common.dto.schedule;

import lombok.Data;

/**
 * 스케줄 설정 변경 요청
 */
@Data
public class ScheduleJobUpdateReqDto {
    private String cronExpression;      // Spring cron (초 분 시 일 월 요일)
    private Boolean enabled;
}
