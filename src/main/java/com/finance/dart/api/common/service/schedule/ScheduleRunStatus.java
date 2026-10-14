package com.finance.dart.api.common.service.schedule;

/**
 * 스케줄 실행 상태
 */
public enum ScheduleRunStatus {
    RUNNING,
    SUCCESS,
    FAILED,
    INTERRUPTED     // 실행 중 서버 재시작(배포 등)으로 끊김
}
