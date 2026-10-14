package com.finance.dart.api.common.dto.schedule;

import com.finance.dart.api.common.entity.ScheduleRunHistoryEntity;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 스케줄 실행 이력 1건
 */
@Data
public class ScheduleRunHistoryDto {
    private Long id;
    private String triggerType;
    private Integer maxCount;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private String message;

    public static ScheduleRunHistoryDto from(ScheduleRunHistoryEntity entity) {
        ScheduleRunHistoryDto dto = new ScheduleRunHistoryDto();
        dto.setId(entity.getId());
        dto.setTriggerType(entity.getTriggerType());
        dto.setMaxCount(entity.getMaxCount());
        dto.setStatus(entity.getStatus());
        dto.setStartedAt(entity.getStartedAt());
        dto.setEndedAt(entity.getEndedAt());
        dto.setMessage(entity.getMessage());
        return dto;
    }
}
