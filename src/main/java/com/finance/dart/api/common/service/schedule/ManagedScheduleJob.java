package com.finance.dart.api.common.service.schedule;

/**
 * 스케줄 관리 화면에서 제어하는 작업
 * - 실행 주기는 ScheduleManagerService가 관리, 구현체는 실행·중복방지·이력 기록만 담당
 */
public interface ManagedScheduleJob {

    /**
     * 작업 종류
     */
    ScheduleJobType type();

    /**
     * 실행 중 여부
     */
    boolean isRunning();

    /**
     * 백그라운드 실행 시작
     * @param trigger 실행 계기
     * @param maxCount 처리 건수 제한 (0 = 전체)
     * @return 시작했으면 true, 이미 실행 중이면 false
     */
    boolean start(ScheduleTriggerType trigger, int maxCount);
}
