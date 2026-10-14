package com.finance.dart.api.common.service.schedule;

import com.finance.dart.api.common.entity.ScheduleRunHistoryEntity;
import com.finance.dart.api.common.repository.ScheduleRunHistoryRepository;
import com.finance.dart.common.util.StringUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 스케줄 실행 이력 기록·조회
 */
@Slf4j
@AllArgsConstructor
@Service
public class ScheduleRunHistoryService {

    /** message 컬럼 최대 바이트 (UTF-8) */
    private static final int MESSAGE_MAX_BYTES = 500;

    /** 서버 재시작으로 끊긴 실행에 남기는 메시지 */
    private static final String INTERRUPTED_MESSAGE = "실행 중 서버가 재시작되어 중단됨 (배포 등)";

    private final ScheduleRunHistoryRepository scheduleRunHistoryRepository;

    /**
     * 실행 시작 기록
     * @return 이력 ID
     */
    @Transactional
    public Long start(ScheduleJobType type, ScheduleTriggerType trigger, int maxCount) {
        ScheduleRunHistoryEntity entity = new ScheduleRunHistoryEntity();
        entity.setJobKey(type.name());
        entity.setTriggerType(trigger.name());
        entity.setMaxCount(maxCount);
        entity.setStatus(ScheduleRunStatus.RUNNING.name());
        entity.setStartedAt(LocalDateTime.now());
        return scheduleRunHistoryRepository.save(entity).getId();
    }

    /**
     * 실행 종료 기록
     */
    @Transactional
    public void finish(Long historyId, ScheduleRunStatus status, String message) {
        scheduleRunHistoryRepository.findById(historyId).ifPresentOrElse(entity -> {
            entity.setStatus(status.name());
            entity.setEndedAt(LocalDateTime.now());
            entity.setMessage(StringUtil.cutStringDelete(message, MESSAGE_MAX_BYTES, "UTF-8"));
        }, () -> log.warn("[스케줄 이력] 종료 기록 대상 없음 id={}", historyId));
    }

    /**
     * 기동 시 정리: 이전 프로세스에서 RUNNING으로 남은 이력을 INTERRUPTED로 변경
     * @return 정리 건수
     */
    @Transactional
    public int markInterrupted() {
        List<ScheduleRunHistoryEntity> stale =
                scheduleRunHistoryRepository.findByStatus(ScheduleRunStatus.RUNNING.name());
        LocalDateTime now = LocalDateTime.now();
        for (ScheduleRunHistoryEntity entity : stale) {
            entity.setStatus(ScheduleRunStatus.INTERRUPTED.name());
            entity.setEndedAt(now);
            entity.setMessage(INTERRUPTED_MESSAGE);
        }
        return stale.size();
    }

    /**
     * 작업별 최근 이력 (최신순)
     */
    @Transactional(readOnly = true)
    public List<ScheduleRunHistoryEntity> getRecent(ScheduleJobType type, int limit) {
        return scheduleRunHistoryRepository.findByJobKeyOrderByStartedAtDesc(type.name(), PageRequest.of(0, limit));
    }

    /**
     * 작업별 마지막 실행
     */
    @Transactional(readOnly = true)
    public Optional<ScheduleRunHistoryEntity> getLast(ScheduleJobType type) {
        return scheduleRunHistoryRepository.findFirstByJobKeyOrderByStartedAtDesc(type.name());
    }
}
