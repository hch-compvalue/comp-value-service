package com.finance.dart.api.domestic.collect.financial;

import com.finance.dart.api.common.service.schedule.*;
import com.finance.dart.api.domestic.collect.financial.KrFinancialCollector.Company;
import com.finance.dart.api.domestic.collect.financial.KrFinancialPlanner.Disclosure;
import com.finance.dart.api.domestic.collect.financial.KrFinancialPlanner.Task;
import com.finance.dart.api.domestic.dart.DartApiException;
import com.finance.dart.api.domestic.dart.DartClient;
import com.finance.dart.api.domestic.dart.DartLimitExceededException;
import com.finance.dart.api.domestic.entity.KrFinancialReportEntity;
import com.finance.dart.api.domestic.entity.KrStockEntity;
import com.finance.dart.api.domestic.repository.KrFinancialReportRepository;
import com.finance.dart.api.domestic.repository.KrFinancialReportRepository.ReportState;
import com.finance.dart.api.domestic.repository.KrStockRepository;
import com.finance.dart.common.logging.TransactionLogging;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 국내 재무 수집 스케줄 (KR_FINANCIAL) — OpenDart 정기보고서
 * 공시 목록(최근 7일 정기보고서) → 새/정정 보고서 수집 → 5년 이력 중 빠진 것 채우기(최신 기간부터)
 * - 하루 OpenDart 호출 15,000건까지 (일일 한도 20,000 중 구 국내 화면 몫 남김 — 수동 실행과 자동 실행 합산), 020(한도 초과)이면 그날 중단
 * - 보고서 단위로 저장하므로 다음 실행이 이어서 받는다 (첫 적재 5년 ≈ 5.6만 건 → 약 4일)
 */
@Slf4j
@Service
public class KrFinancialJob implements ManagedScheduleJob {

    static final int DAILY_CALL_BUDGET = 15_000;
    static final int WORKER_THREADS = 3;
    static final int MAX_CONSECUTIVE_FAILURES = 30;
    static final double FAILURE_TOLERANCE_RATIO = 0.01;
    static final int DISCLOSURE_SCAN_DAYS = 7;
    /** 한 보고서 최대 호출 (연결 → 별도) */
    private static final int MAX_CALLS_PER_TASK = 2;
    private static final int LIST_PAGE_SIZE = 100;
    private static final int MAX_LIST_PAGES = 100;
    private static final long MAX_RUN_HOURS = 5;
    private static final int PROGRESS_LOG_EVERY = 1_000;
    private static final String ACTIVE = "Y";
    private static final int MONTHS_PER_YEAR = 12;
    private static final String PERIODIC_REPORTS = "A";
    private static final Set<String> LISTED_CLASSES = Set.of("Y", "K");   // 코스피·코스닥
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ExecutorService workers;

    private final DartClient dartClient;
    private final KrFinancialCollector collector;
    private final KrFinancialReportRepository reportRepository;
    private final KrStockRepository krStockRepository;
    private final ScheduleRunHistoryService scheduleRunHistoryService;

    public KrFinancialJob(DartClient dartClient, KrFinancialCollector collector,
                          KrFinancialReportRepository reportRepository, KrStockRepository krStockRepository,
                          ScheduleRunHistoryService scheduleRunHistoryService) {
        this.dartClient = dartClient;
        this.collector = collector;
        this.reportRepository = reportRepository;
        this.krStockRepository = krStockRepository;
        this.scheduleRunHistoryService = scheduleRunHistoryService;
    }

    @Override
    public ScheduleJobType type() {
        return ScheduleJobType.KR_FINANCIAL;
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /**
     * @param maxCount 받을 보고서 수 제한 (0이면 예산까지)
     */
    @Override
    @TransactionLogging
    public boolean start(ScheduleTriggerType trigger, int maxCount) {
        if (!running.compareAndSet(false, true)) {
            log.warn("[국내 재무] 이미 실행 중 — {} 실행 요청 거절", trigger);
            return false;
        }

        Long historyId = null;
        try {
            historyId = scheduleRunHistoryService.start(type(), trigger, maxCount);
            final Long id = historyId;
            executorService.submit(() -> runInBackground(id, maxCount));
            return true;
        } catch (RejectedExecutionException e) {
            log.error("[국내 재무] 실행 대기열 등록 실패", e);
            if (historyId != null) scheduleRunHistoryService.finish(historyId, ScheduleRunStatus.FAILED, "실행 대기열 등록 실패");
            running.set(false);
            return false;
        } catch (RuntimeException e) {
            running.set(false);     // 이력 저장 실패 등 — 플래그가 남으면 이후 실행이 영구히 막힘
            throw e;
        }
    }

    private void runInBackground(Long historyId, int maxCount) {
        ScheduleRunStatus status = ScheduleRunStatus.FAILED;
        String message = "알 수 없는 오류 (로그 확인)";
        try {
            int callsBefore = dartClient.callsToday();
            LocalDateTime now = LocalDateTime.now(ScheduleManagerService.SCHEDULE_ZONE);
            Map<String, Company> companies = loadCompanies();
            List<ReportState> stateList = reportRepository.findAllStates();
            Map<String, ReportState> states = KrFinancialPlanner.index(stateList);
            Map<String, String> preferredFs = KrFinancialPlanner.preferredFs(stateList);

            List<Disclosure> disclosures = scanDisclosures(now.toLocalDate());
            List<Task> newReports = KrFinancialPlanner.fromDisclosures(disclosures, companies, states);
            Set<String> planned = new HashSet<>();
            newReports.forEach(t -> planned.add(KrFinancialPlanner.key(t.company().stockCode(), t.target().bsnsYear(), t.target().type().code())));
            List<Task> backfill = KrFinancialPlanner.backfill(companies.values(), states, planned, now);

            List<Task> tasks = new ArrayList<>(newReports);
            tasks.addAll(backfill);
            if (maxCount > 0 && tasks.size() > maxCount) tasks = tasks.subList(0, maxCount);

            Summary summary = execute(tasks, preferredFs);
            status = summary.isSuccess() ? ScheduleRunStatus.SUCCESS : ScheduleRunStatus.FAILED;
            message = String.format("공시 %d건(수집 대상 %d) / 이력 채우기 대상 %d / %s / OpenDart %d건 호출",
                    disclosures.size(), newReports.size(), backfill.size(), summary.summary(), dartClient.callsToday() - callsBefore);
        } catch (DartApiException | DataAccessException e) {
            log.error("[국내 재무] 중단", e);
            message = "중단: " + e.getMessage();
        } catch (RuntimeException e) {
            log.error("[국내 재무] 처리 중 예기치 못한 오류", e);
            message = "처리 중 오류: " + e.getMessage();
        } finally {
            log.info("[국내 재무] 종료 {} — {}", status, message);
            finishHistory(historyId, status, message);
            running.set(false);
        }
    }

    /** 고유번호·결산월이 있는 활성 종목 */
    private Map<String, Company> loadCompanies() {
        Map<String, Company> companies = new LinkedHashMap<>();
        for (KrStockEntity s : krStockRepository.findByActiveOrderByStockCode(ACTIVE)) {
            Integer fiscalMonth = parseMonth(s.getFiscalMonth());
            if (s.getCorpCode() == null || fiscalMonth == null) continue;
            companies.put(s.getStockCode(), new Company(s.getStockCode(), s.getCorpCode(), fiscalMonth));
        }
        return companies;
    }

    /** 최근 정기보고서 공시 목록 (코스피·코스닥만) */
    private List<Disclosure> scanDisclosures(LocalDate today) {
        List<Disclosure> result = new ArrayList<>();
        int totalPages = 1;
        for (int page = 1; page <= totalPages && page <= MAX_LIST_PAGES; page++) {
            DartClient.Result r = dartClient.get("list.json", Map.of(
                    "bgn_de", today.minusDays(DISCLOSURE_SCAN_DAYS).format(YYYYMMDD),
                    "end_de", today.format(YYYYMMDD),
                    "pblntf_ty", PERIODIC_REPORTS,
                    "page_no", String.valueOf(page),
                    "page_count", String.valueOf(LIST_PAGE_SIZE)));
            totalPages = toInt(r.body().get("total_page"));
            for (Map<String, Object> row : r.rows()) {
                if (!LISTED_CLASSES.contains(String.valueOf(row.get("corp_cls")))) continue;
                LocalDate rceptDate = parseDate(String.valueOf(row.get("rcept_dt")));
                if (rceptDate == null) continue;
                result.add(new Disclosure(String.valueOf(row.get("stock_code")).trim(), String.valueOf(row.get("report_nm")), rceptDate));
            }
        }
        return result;
    }

    private Summary execute(List<Task> tasks, Map<String, String> preferredFs) {
        Summary summary = new Summary(tasks.size());
        ExecutorService pool = Executors.newFixedThreadPool(WORKER_THREADS);
        workers = pool;
        try {
            for (Task task : tasks) pool.submit(() -> runTask(task, preferredFs.get(task.company().stockCode()), summary));
            pool.shutdown();
            if (!pool.awaitTermination(MAX_RUN_HOURS, TimeUnit.HOURS)) {
                pool.shutdownNow();
                summary.stop("시간 초과");
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            summary.stop("인터럽트");
            Thread.currentThread().interrupt();
        } finally {
            workers = null;
        }
        return summary;
    }

    private void runTask(Task task, String preferredFs, Summary summary) {
        if (summary.stopped.get()) {
            summary.skipped.incrementAndGet();
            return;
        }
        if (dartClient.callsToday() + MAX_CALLS_PER_TASK > DAILY_CALL_BUDGET) {
            summary.stop("오늘 호출 예산 " + DAILY_CALL_BUDGET + "건 소진");
            summary.skipped.incrementAndGet();
            return;
        }
        try {
            KrFinancialReportEntity saved = collector.collect(task.company(), task.target(), preferredFs);
            summary.record(!KrFinancialCollector.FS_NONE.equals(saved.getFsDiv()));
        } catch (DartLimitExceededException e) {
            summary.stop("OpenDart 일일 한도 초과");
            summary.skipped.incrementAndGet();
        } catch (DartApiException | DataAccessException e) {
            log.warn("[국내 재무] {} {} {} 실패: {}", task.company().stockCode(), task.target().bsnsYear(), task.target().type(), e.getMessage());
            summary.recordFailure();
        }
        int done = summary.done.incrementAndGet();
        if (done % PROGRESS_LOG_EVERY == 0) log.info("[국내 재무] 진행 {}/{} — {}", done, summary.total, summary.summary());
    }

    private void finishHistory(Long historyId, ScheduleRunStatus status, String message) {
        try {
            scheduleRunHistoryService.finish(historyId, status, message);
        } catch (RuntimeException e) {
            log.error("[국내 재무] 실행 이력 종료 기록 실패 id={}", historyId, e);
        }
    }

    @PreDestroy
    public void shutdownExecutor() {
        ExecutorService current = workers;
        if (current != null) current.shutdownNow();     // 저장 전 보고서는 다음 실행이 다시 받음
        executorService.shutdownNow();
    }

    private static Integer parseMonth(String month) {
        if (month == null) return null;
        try {
            int m = Integer.parseInt(month.trim());
            return m >= 1 && m <= MONTHS_PER_YEAR ? m : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate parseDate(String yyyymmdd) {
        try {
            return LocalDate.parse(yyyymmdd, YYYYMMDD);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static int toInt(Object value) {
        try {
            return value == null ? 0 : Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 수집 집계 (스레드 공유) */
    static final class Summary {
        final int total;
        final AtomicInteger done = new AtomicInteger();
        final AtomicInteger found = new AtomicInteger();
        final AtomicInteger none = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final AtomicInteger skipped = new AtomicInteger();
        final AtomicInteger consecutiveFailures = new AtomicInteger();
        final AtomicBoolean stopped = new AtomicBoolean(false);
        final AtomicBoolean aborted = new AtomicBoolean(false);
        private volatile String stopReason;

        Summary(int total) {
            this.total = total;
        }

        void record(boolean hasData) {
            consecutiveFailures.set(0);
            (hasData ? found : none).incrementAndGet();
        }

        void recordFailure() {
            failed.incrementAndGet();
            if (consecutiveFailures.incrementAndGet() >= MAX_CONSECUTIVE_FAILURES) {
                aborted.set(true);
                stop("연속 " + MAX_CONSECUTIVE_FAILURES + "건 실패");
            }
        }

        /** 예산·한도 소진은 정상 중단 (다음 실행이 이어 받음), 연속 실패만 실패 */
        void stop(String reason) {
            if (stopped.compareAndSet(false, true)) {
                stopReason = reason;
                log.warn("[국내 재무] 중단 — {}", reason);
            }
        }

        boolean isSuccess() {
            int attempted = found.get() + none.get() + failed.get();
            return !aborted.get() && failed.get() <= attempted * FAILURE_TOLERANCE_RATIO;
        }

        String summary() {
            return String.format("보고서 %d건: 수집 %d, 데이터 없음 %d, 실패 %d%s",
                    total, found.get(), none.get(), failed.get(),
                    stopped.get() ? ", 중단(" + stopReason + ", 남은 " + skipped.get() + "건은 다음 실행)" : "");
        }
    }
}
