package com.finance.dart.api.common.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.dart.api.common.dto.evaluation.EvaluationJobDto;
import com.finance.dart.api.common.dto.evaluation.StockEvaluationRequest;
import com.finance.dart.api.common.dto.evaluation.StockEvaluationResponse;
import com.finance.dart.common.exception.BizException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 일괄 평가 작업 — 종목 목록을 서버에서 순서대로 평가 (화면을 막지 않음)
 * - 회원당 진행 중 작업 1개 (다른 창·기기에서 다시 시작해도 거부)
 * - 진행 상태·결과는 Redis 에 24시간 보관 → 화면 이동·새로고침 후에도 이어서 조회
 * - 호출 속도는 StockEvaluationService 의 FmpRateLimiter 가 조절 (다른 작업·스케줄과 한도 공유)
 * - 서버 재시작 시 진행 중이던 작업은 INTERRUPTED (그때까지 결과는 유지)
 */
@Slf4j
@Service
public class EvaluationJobService {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_INTERRUPTED = "INTERRUPTED";
    public static final String STATUS_FAILED = "FAILED";

    private static final int MAX_JOB_SYMBOLS = 2000;
    private static final int WORKER_THREADS = 2;
    private static final long KEEP_HOURS = 24;
    private static final long SHUTDOWN_WAIT_SECONDS = 5;
    private static final String KEY_PREFIX = "evaljob:";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final StockEvaluationService stockEvaluationService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;

    /** 이 서버에서 진행 중인 작업 (회원 ID → 중지 요청 표시) */
    private final Map<Long, AtomicBoolean> runningJobs = new ConcurrentHashMap<>();

    public EvaluationJobService(StockEvaluationService stockEvaluationService,
                                RedisTemplate<String, Object> redisTemplate,
                                ObjectMapper objectMapper) {
        this.stockEvaluationService = stockEvaluationService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        AtomicInteger seq = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(WORKER_THREADS, r -> {
            Thread t = new Thread(r, "evaluation-job-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 작업 시작 — 같은 회원의 작업이 진행 중이면 거부
     */
    public EvaluationJobDto start(Long memberId, List<String> rawSymbols) {
        List<String> symbols = normalize(rawSymbols);
        if (symbols.isEmpty()) {
            throw new BizException("평가할 종목이 없습니다.");
        }
        if (symbols.size() > MAX_JOB_SYMBOLS) {
            throw new BizException("한 번에 " + MAX_JOB_SYMBOLS + "종목까지 평가할 수 있습니다. (요청 " + symbols.size() + "종목)");
        }

        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        if (runningJobs.putIfAbsent(memberId, cancelFlag) != null) {
            EvaluationJobDto current = readMeta(memberId);
            String progress = current != null ? " (" + current.getDone() + "/" + current.getTotal() + ")" : "";
            throw new BizException("이미 진행 중인 일괄 평가가 있습니다" + progress + ". 끝나거나 중지한 뒤 다시 시작해 주세요.");
        }

        EvaluationJobDto meta = EvaluationJobDto.builder()
                .jobId(UUID.randomUUID().toString())
                .status(STATUS_RUNNING)
                .total(symbols.size())
                .done(0)
                .startedAt(now())
                .build();
        try {
            redisTemplate.delete(resultsKey(memberId));
            writeMeta(memberId, meta);
            executor.submit(() -> run(memberId, meta, symbols, cancelFlag));
        } catch (RuntimeException e) { // Redis 오류·실행 거부 — 잠금 해제 후 그대로 알림
            runningJobs.remove(memberId);
            throw e;
        }
        log.info("[일괄평가] 시작 member={} job={} 종목={}", memberId, meta.getJobId(), symbols.size());
        return meta;
    }

    /**
     * 현재(또는 마지막) 작업 — 없으면 null
     * @param from 이미 받은 결과 수 (그 뒤부터 반환)
     */
    public EvaluationJobDto getCurrent(Long memberId, int from) {
        EvaluationJobDto meta = readMeta(memberId);
        if (meta == null) return null;

        // 진행 중으로 남아 있는데 이 서버에 실행 중인 작업이 없으면 재시작으로 끊긴 것
        if (STATUS_RUNNING.equals(meta.getStatus()) && !runningJobs.containsKey(memberId)) {
            meta.setStatus(STATUS_INTERRUPTED);
            meta.setMessage("서버가 재시작되어 작업이 중단되었습니다.");
            meta.setFinishedAt(now());
            writeMeta(memberId, meta);
        }

        int start = Math.max(0, from);
        List<Object> raw = redisTemplate.opsForList().range(resultsKey(memberId), start, -1);
        List<JsonNode> results = new ArrayList<>();
        if (raw != null) {
            for (Object item : raw) {
                try {
                    results.add(objectMapper.readTree(String.valueOf(item)));
                } catch (JsonProcessingException e) {
                    log.warn("[일괄평가] 결과 파싱 실패 member={}: {}", memberId, e.getMessage());
                }
            }
        }
        meta.setResultsFrom(start);
        meta.setResults(results);
        return meta;
    }

    /**
     * 중지 요청 — 지금 평가 중인 종목까지 끝내고 멈춤
     */
    public EvaluationJobDto cancel(Long memberId) {
        AtomicBoolean flag = runningJobs.get(memberId);
        if (flag == null) {
            throw new BizException("진행 중인 일괄 평가가 없습니다.");
        }
        flag.set(true);
        log.info("[일괄평가] 중지 요청 member={}", memberId);
        return readMeta(memberId);
    }

    private void run(Long memberId, EvaluationJobDto meta, List<String> symbols, AtomicBoolean cancelFlag) {
        try {
            for (String symbol : symbols) {
                if (cancelFlag.get()) {
                    meta.setStatus(STATUS_CANCELLED);
                    break;
                }
                if (Thread.currentThread().isInterrupted()) {
                    meta.setStatus(STATUS_INTERRUPTED);
                    meta.setMessage("서버가 종료되어 작업이 중단되었습니다.");
                    break;
                }
                StockEvaluationRequest req = new StockEvaluationRequest();
                req.setSymbols(List.of(symbol));
                // evaluateStocks 는 종목별 오류를 결과(grade=ERROR)로 담아 돌려줌
                for (StockEvaluationResponse r : stockEvaluationService.evaluateStocks(req)) {
                    redisTemplate.opsForList().rightPush(resultsKey(memberId), objectMapper.writeValueAsString(r));
                }
                redisTemplate.expire(resultsKey(memberId), KEEP_HOURS, TimeUnit.HOURS);
                meta.setDone(meta.getDone() + 1);
                writeMeta(memberId, meta);
            }
            if (STATUS_RUNNING.equals(meta.getStatus())) {
                meta.setStatus(STATUS_DONE);
            }
        } catch (JsonProcessingException | DataAccessException e) {
            log.error("[일괄평가] 저장 실패 member={} job={}", memberId, meta.getJobId(), e);
            meta.setStatus(STATUS_FAILED);
            meta.setMessage("결과 저장 중 오류가 발생했습니다: " + e.getMessage());
        } catch (RuntimeException e) { // 평가 서비스 내부 예외 — 작업만 실패 처리하고 잠금은 반드시 해제
            log.error("[일괄평가] 실패 member={} job={}", memberId, meta.getJobId(), e);
            meta.setStatus(STATUS_FAILED);
            meta.setMessage("평가 중 오류가 발생했습니다: " + e.getMessage());
        } finally {
            meta.setFinishedAt(now());
            try {
                writeMeta(memberId, meta);
            } catch (DataAccessException e) {
                log.error("[일괄평가] 최종 상태 저장 실패 member={}", memberId, e);
            }
            runningJobs.remove(memberId);
            log.info("[일괄평가] 종료 member={} job={} 상태={} {}/{}", memberId, meta.getJobId(), meta.getStatus(), meta.getDone(), meta.getTotal());
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<String> normalize(List<String> symbols) {
        if (symbols == null) return List.of();
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String s : symbols) {
            if (s == null) continue;
            String v = s.trim().toUpperCase(Locale.ROOT);
            if (!v.isEmpty()) set.add(v);
        }
        return new ArrayList<>(set);
    }

    private EvaluationJobDto readMeta(Long memberId) {
        Object raw = redisTemplate.opsForValue().get(metaKey(memberId));
        if (raw == null) return null;
        try {
            return objectMapper.readValue(String.valueOf(raw), EvaluationJobDto.class);
        } catch (JsonProcessingException e) {
            log.warn("[일괄평가] 상태 파싱 실패 member={}: {}", memberId, e.getMessage());
            return null;
        }
    }

    private void writeMeta(Long memberId, EvaluationJobDto meta) {
        EvaluationJobDto copy = EvaluationJobDto.builder()
                .jobId(meta.getJobId()).status(meta.getStatus()).total(meta.getTotal()).done(meta.getDone())
                .startedAt(meta.getStartedAt()).finishedAt(meta.getFinishedAt()).message(meta.getMessage())
                .build();
        try {
            redisTemplate.opsForValue().set(metaKey(memberId), objectMapper.writeValueAsString(copy), KEEP_HOURS, TimeUnit.HOURS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("일괄 평가 상태 직렬화 실패", e);
        }
    }

    private static String metaKey(Long memberId) {
        return KEY_PREFIX + memberId + ":meta";
    }

    private static String resultsKey(Long memberId) {
        return KEY_PREFIX + memberId + ":results";
    }

    private static String now() {
        return LocalDateTime.now().format(TIME_FORMAT);
    }
}
