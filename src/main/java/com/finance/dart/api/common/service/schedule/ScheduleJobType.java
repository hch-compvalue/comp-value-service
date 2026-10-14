package com.finance.dart.api.common.service.schedule;

import lombok.Getter;

/**
 * 화면에서 관리하는 스케줄 작업 정의
 * - 작업을 추가하면 ManagedScheduleJob 구현 빈 + 여기 항목을 함께 추가
 */
@Getter
public enum ScheduleJobType {

    RECOMMENDED_STOCKS(
            "추천 종목 수집·전수 평가",
            "활성 추천 프로파일로 미국 종목을 스크리닝한 뒤 추천 종목 전체를 평가해 평가 이력에 저장 (약 4시간, FMP 사용)",
            "0 0 10 * * *"),   // 10시 - 미국장 종가 확정 후, 야간은 PC 꺼짐으로 누락되므로 낮 시간 유지
    ;

    private final String label;
    private final String description;
    private final String defaultCron;

    ScheduleJobType(String label, String description, String defaultCron) {
        this.label = label;
        this.description = description;
        this.defaultCron = defaultCron;
    }
}
