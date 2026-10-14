package com.finance.dart.api.common.service.schedule;

import com.finance.dart.api.common.dto.schedule.ScheduleJobDto;
import com.finance.dart.api.common.dto.schedule.ScheduleRunHistoryDto;
import com.finance.dart.api.common.entity.ScheduleJobEntity;
import com.finance.dart.api.common.repository.ScheduleJobRepository;
import com.finance.dart.common.constant.ResponseEnum;
import com.finance.dart.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 스케줄 관리
 * - 기존 @Scheduled(코드 고정 cron) 대신 DB 설정(tb_schedule_job)으로 TaskScheduler에 등록 → 화면에서 주기·사용여부 변경 시 재배포 없이 즉시 반영
 * - 실행 자체·중복 방지·이력은 각 ManagedScheduleJob 구현체가 담당
 */
@Slf4j
@Service
public class ScheduleManagerService {

    /** 스케줄 시간대 (파드 TZ와 동일하게 명시) */
    public static final ZoneId SCHEDULE_ZONE = ZoneId.of("Asia/Seoul");

    /** 자동 실행은 항상 전체 처리 */
    private static final int AUTO_RUN_MAX_COUNT = 0;

    /** 사용 여부 플래그 */
    private static final String FLAG_YES = "Y";
    private static final String FLAG_NO = "N";

    /** 이력 조회 최대 건수 */
    private static final int MAX_HISTORY_LIMIT = 100;

    private final TaskScheduler taskScheduler;
    private final ScheduleJobRepository scheduleJobRepository;
    private final ScheduleRunHistoryService scheduleRunHistoryService;
    private final Map<ScheduleJobType, ManagedScheduleJob> jobs;

    /** 등록된 자동 실행 트리거 (비활성 작업은 없음) */
    private final Map<ScheduleJobType, ScheduledFuture<?>> scheduledFutures = new EnumMap<>(ScheduleJobType.class);

    public ScheduleManagerService(TaskScheduler taskScheduler,
                                  ScheduleJobRepository scheduleJobRepository,
                                  ScheduleRunHistoryService scheduleRunHistoryService,
                                  List<ManagedScheduleJob> jobList) {
        this.taskScheduler = taskScheduler;
        this.scheduleJobRepository = scheduleJobRepository;
        this.scheduleRunHistoryService = scheduleRunHistoryService;
        this.jobs = jobList.stream().collect(Collectors.toMap(
                ManagedScheduleJob::type, Function.identity(), (a, b) -> a, () -> new EnumMap<>(ScheduleJobType.class)));
    }

    /**
     * 기동 시: 끊긴 실행 이력 정리 → 설정 로드(없으면 기본값 생성) → 활성 작업 트리거 등록
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initSchedules() {
        int interrupted = scheduleRunHistoryService.markInterrupted();
        if (interrupted > 0) log.warn("[스케줄 관리] 이전 실행 중 끊긴 이력 {}건 → INTERRUPTED 처리", interrupted);

        for (ScheduleJobType type : jobs.keySet()) {
            ScheduleJobEntity config = loadOrCreateConfig(type);
            applySchedule(type, config);
        }
    }

    /**
     * 작업 목록 + 현재 상태
     */
    public List<ScheduleJobDto> getJobs() {
        List<ScheduleJobDto> result = new ArrayList<>();
        for (ScheduleJobType type : jobs.keySet()) {
            result.add(toDto(type, loadOrCreateConfig(type)));
        }
        return result;
    }

    /**
     * 실행 주기·사용 여부 변경 (실행 중인 작업은 끊지 않고 다음 실행부터 반영)
     */
    public ScheduleJobDto updateJob(String jobKey, String cronExpression, Boolean enabled) {
        ScheduleJobType type = resolveType(jobKey);
        ScheduleJobEntity config = loadOrCreateConfig(type);

        if (cronExpression != null) {
            String cron = cronExpression.trim();
            if (!CronExpression.isValidExpression(cron)) throw new BizException(ResponseEnum.SCHEDULE_INVALID_CRON);
            config.setCronExpression(cron);
        }
        if (enabled != null) config.setEnabled(enabled ? FLAG_YES : FLAG_NO);

        ScheduleJobEntity saved = scheduleJobRepository.save(config);
        applySchedule(type, saved);
        log.info("[스케줄 관리] 설정 변경 {} cron='{}' enabled={}", type, saved.getCronExpression(), saved.getEnabled());
        return toDto(type, saved);
    }

    /**
     * 지금 실행
     * @param maxCount 처리 건수 제한 (null/0 이하 = 전체)
     */
    public void runNow(String jobKey, Integer maxCount) {
        ScheduleJobType type = resolveType(jobKey);
        int count = (maxCount == null || maxCount < 0) ? AUTO_RUN_MAX_COUNT : maxCount;
        if (!jobs.get(type).start(ScheduleTriggerType.MANUAL, count)) {
            throw new BizException(ResponseEnum.SCHEDULE_ALREADY_RUNNING);
        }
        log.info("[스케줄 관리] 수동 실행 {} maxCount={}", type, count);
    }

    /**
     * 작업별 최근 실행 이력
     */
    public List<ScheduleRunHistoryDto> getHistory(String jobKey, int limit) {
        ScheduleJobType type = resolveType(jobKey);
        int size = Math.max(1, Math.min(limit, MAX_HISTORY_LIMIT));
        return scheduleRunHistoryService.getRecent(type, size).stream()
                .map(ScheduleRunHistoryDto::from)
                .toList();
    }

    /**
     * 트리거 재등록 (기존 트리거 취소 → 활성이면 새 cron으로 등록)
     */
    private synchronized void applySchedule(ScheduleJobType type, ScheduleJobEntity config) {
        ScheduledFuture<?> previous = scheduledFutures.remove(type);
        if (previous != null) previous.cancel(false);   // 다음 실행 예약만 취소, 실행 중인 작업은 유지

        if (!FLAG_YES.equals(config.getEnabled())) {
            log.info("[스케줄 관리] {} 비활성 — 자동 실행 안 함", type);
            return;
        }

        ManagedScheduleJob job = jobs.get(type);
        CronTrigger trigger = new CronTrigger(config.getCronExpression(), SCHEDULE_ZONE);
        ScheduledFuture<?> future = taskScheduler.schedule(() -> startAuto(job), trigger);
        scheduledFutures.put(type, future);
        log.info("[스케줄 관리] {} 등록 cron='{}' 다음 실행={}", type, config.getCronExpression(), nextRunAt(config));
    }

    /**
     * 자동 실행 (이미 실행 중이면 이번 회차 건너뜀)
     */
    private void startAuto(ManagedScheduleJob job) {
        if (!job.start(ScheduleTriggerType.AUTO, AUTO_RUN_MAX_COUNT)) {
            log.warn("[스케줄 관리] {} 이전 실행이 아직 진행 중 — 이번 자동 실행 건너뜀", job.type());
        }
    }

    /**
     * 설정 조회, 없으면 코드 기본값으로 생성
     */
    private ScheduleJobEntity loadOrCreateConfig(ScheduleJobType type) {
        return scheduleJobRepository.findById(type.name()).orElseGet(() -> {
            ScheduleJobEntity entity = new ScheduleJobEntity();
            entity.setJobKey(type.name());
            entity.setCronExpression(type.getDefaultCron());
            entity.setEnabled(FLAG_YES);
            log.info("[스케줄 관리] {} 설정 없음 → 기본값 생성 cron='{}'", type, type.getDefaultCron());
            return scheduleJobRepository.save(entity);
        });
    }

    private ScheduleJobType resolveType(String jobKey) {
        ScheduleJobType type = Arrays.stream(ScheduleJobType.values())
                .filter(t -> t.name().equals(jobKey))
                .findFirst()
                .orElseThrow(() -> new BizException(ResponseEnum.SCHEDULE_UNKNOWN_JOB));
        if (!jobs.containsKey(type)) throw new BizException(ResponseEnum.SCHEDULE_UNKNOWN_JOB);
        return type;
    }

    private LocalDateTime nextRunAt(ScheduleJobEntity config) {
        if (!FLAG_YES.equals(config.getEnabled())) return null;
        ZonedDateTime next = CronExpression.parse(config.getCronExpression()).next(ZonedDateTime.now(SCHEDULE_ZONE));
        return next == null ? null : next.toLocalDateTime();
    }

    private ScheduleJobDto toDto(ScheduleJobType type, ScheduleJobEntity config) {
        ScheduleJobDto dto = new ScheduleJobDto();
        dto.setJobKey(type.name());
        dto.setLabel(type.getLabel());
        dto.setDescription(type.getDescription());
        dto.setCronExpression(config.getCronExpression());
        dto.setDefaultCron(type.getDefaultCron());
        dto.setEnabled(FLAG_YES.equals(config.getEnabled()));
        dto.setRunning(jobs.get(type).isRunning());
        dto.setNextRunAt(nextRunAt(config));
        dto.setUpdatedAt(config.getUpdatedAt());
        dto.setLastRun(scheduleRunHistoryService.getLast(type).map(ScheduleRunHistoryDto::from).orElse(null));
        return dto;
    }
}
