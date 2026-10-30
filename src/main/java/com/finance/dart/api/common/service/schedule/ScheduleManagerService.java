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
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 스케줄 관리
 * - 기존 @Scheduled(코드 고정 cron) 대신 DB 설정(tb_schedule_job) → 화면에서 주기·사용여부 변경 시 재배포 없이 즉시 반영
 * - 1분마다 벽시계로 "예정 시각이 지났고 아직 실행 안 한 회차"를 실행 (2026-10-30 변경)
 *   · 이전 CronTrigger 방식은 다음 실행까지 남은 시간을 단조 시계로 재서, PC 절전(WSL 일시정지) 시간만큼 이후 실행이 계속 밀렸다
 *     (10-30: 절전 40분 → 16:00 국내 일봉이 16:40 시작)
 *   · 따라잡기는 예정 시각부터 3시간 안까지만 — 전날 놓친 회차를 다음 날 아침에 돌리지 않는다
 *   · 그 회차 이후 실행 이력(수동 포함)이 있으면 다시 돌리지 않는다 (배포 재시작 시 중복 방지)
 * - 실행 자체·중복 방지·이력은 각 ManagedScheduleJob 구현체가 담당
 */
@Slf4j
@Service
public class ScheduleManagerService {

    /** 스케줄 시간대 (파드 TZ와 동일하게 명시) */
    public static final ZoneId SCHEDULE_ZONE = ZoneId.of("Asia/Seoul");

    /** k8s DB 백업 CronJob이 실행 이력에 기록하는 job_key (gos ncloud/backend/db/backup-cronjob.yml과 일치) */
    public static final String DB_BACKUP_JOB_KEY = "DB_BACKUP";

    /** 자동 실행은 항상 전체 처리 */
    private static final int AUTO_RUN_MAX_COUNT = 0;

    /** 사용 여부 플래그 */
    private static final String FLAG_YES = "Y";
    private static final String FLAG_NO = "N";

    /** 이력 조회 최대 건수 */
    private static final int MAX_HISTORY_LIMIT = 100;

    /** 예정 시각 확인 주기 */
    static final Duration CHECK_INTERVAL = Duration.ofMinutes(1);
    /** 예정 시각이 지난 회차를 따라잡아 실행하는 한도 */
    static final Duration CATCH_UP_WINDOW = Duration.ofHours(3);
    /** 이보다 늦게 실행하면 경고 로그 (절전 등) */
    private static final Duration LATE_WARN_THRESHOLD = Duration.ofMinutes(5);

    private final TaskScheduler taskScheduler;
    private final ScheduleJobRepository scheduleJobRepository;
    private final ScheduleRunHistoryService scheduleRunHistoryService;
    private final Map<ScheduleJobType, ManagedScheduleJob> jobs;

    /** 현재 설정 (기동·변경 시 갱신, 1분 확인이 읽음) */
    private final Map<ScheduleJobType, ScheduleJobEntity> configs = new EnumMap<>(ScheduleJobType.class);
    /** 작업별 마지막으로 처리한 예정 회차 (같은 회차를 두 번 실행하지 않게) */
    private final Map<ScheduleJobType, ZonedDateTime> handledSlots = new EnumMap<>(ScheduleJobType.class);
    private ScheduledFuture<?> checker;

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
        checker = taskScheduler.scheduleWithFixedDelay(this::checkDueJobsSafely, CHECK_INTERVAL);
    }

    @PreDestroy
    public void stopChecker() {
        if (checker != null) checker.cancel(false);
    }

    /** 확인 중 예외가 나도 다음 확인이 계속 돌게 (scheduleWithFixedDelay 는 예외 시 반복을 멈춘다) */
    private void checkDueJobsSafely() {
        try {
            checkDueJobs(ZonedDateTime.now(SCHEDULE_ZONE));
        } catch (RuntimeException e) {
            log.error("[스케줄 관리] 예정 시각 확인 실패", e);
        }
    }

    /**
     * 예정 시각이 지난(3시간 안) 회차 중 아직 처리하지 않았고 그 뒤 실행 이력도 없는 작업을 자동 실행
     */
    synchronized void checkDueJobs(ZonedDateTime now) {
        for (Map.Entry<ScheduleJobType, ScheduleJobEntity> e : configs.entrySet()) {
            ScheduleJobType type = e.getKey();
            ScheduleJobEntity config = e.getValue();
            if (!FLAG_YES.equals(config.getEnabled())) continue;
            ZonedDateTime slot = latestDueSlot(CronExpression.parse(config.getCronExpression()), now);
            if (slot == null || slot.equals(handledSlots.get(type))) continue;
            handledSlots.put(type, slot);

            boolean ranSinceSlot = scheduleRunHistoryService.getLast(type)
                    .map(h -> !h.getStartedAt().isBefore(slot.toLocalDateTime()))
                    .orElse(false);
            if (ranSinceSlot) {
                log.info("[스케줄 관리] {} {} 회차는 이미 실행됨 — 건너뜀", type, slot.toLocalDateTime());
                continue;
            }
            if (Duration.between(slot, now).compareTo(LATE_WARN_THRESHOLD) > 0) {
                log.warn("[스케줄 관리] {} 예정 {} → {}분 늦게 실행 (PC 절전·재시작 등)", type, slot.toLocalDateTime(), Duration.between(slot, now).toMinutes());
            }
            startAuto(jobs.get(type));
        }
    }

    /** now 이전(포함) CATCH_UP_WINDOW 안의 마지막 예정 시각 (없으면 null) */
    static ZonedDateTime latestDueSlot(CronExpression cron, ZonedDateTime now) {
        ZonedDateTime last = null;
        ZonedDateTime t = cron.next(now.minus(CATCH_UP_WINDOW).minusSeconds(1));
        while (t != null && !t.isAfter(now)) {
            last = t;
            t = cron.next(t);
        }
        return last;
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
     * 외부 작업(k8s DB 백업 CronJob)의 최근 실행 이력
     * - 백업 스크립트가 끝날 때 tb_schedule_run_history에 job_key=DB_BACKUP으로 직접 기록 (gos backup-cronjob.yml)
     */
    public List<ScheduleRunHistoryDto> getDbBackupHistory(int limit) {
        int size = Math.max(1, Math.min(limit, MAX_HISTORY_LIMIT));
        return scheduleRunHistoryService.getRecent(DB_BACKUP_JOB_KEY, size).stream()
                .map(ScheduleRunHistoryDto::from)
                .toList();
    }

    /**
     * 설정 반영 — 1분 확인이 새 설정을 읽는다
     * - 변경 시점에 이미 지난 회차는 처리한 것으로 본다 (시각을 앞당겨 저장하자마자 바로 실행되는 것 방지)
     */
    private synchronized void applySchedule(ScheduleJobType type, ScheduleJobEntity config) {
        boolean firstLoad = !configs.containsKey(type);
        configs.put(type, config);
        if (!firstLoad) {
            handledSlots.put(type, latestDueSlot(CronExpression.parse(config.getCronExpression()), ZonedDateTime.now(SCHEDULE_ZONE)));
        }
        if (!FLAG_YES.equals(config.getEnabled())) {
            log.info("[스케줄 관리] {} 비활성 — 자동 실행 안 함", type);
            return;
        }
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
