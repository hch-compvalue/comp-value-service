package com.finance.dart.monitoring.service;

import com.finance.dart.monitoring.dto.PodStatusDto;
import com.finance.dart.monitoring.service.PrometheusQueryService.Sample;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * 클러스터 전체 파드 상태 — Prometheus(kube-state-metrics, cAdvisor) 지표를 파드 단위로 합침
 * - 네임스페이스 제한 없음 (시스템 파드 포함)
 * - 쿼리 하나가 실패해도 나머지 정보로 목록을 만듦
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PodStatusService {

    private static final double BYTES_PER_MB = 1024 * 1024;
    private static final String NS = "namespace";
    private static final String POD = "pod";

    private final PrometheusQueryService prometheus;

    public List<PodStatusDto> getPods() {
        if (!prometheus.isEnabled()) return List.of();

        // 파드 목록의 기준: kube_pod_info (네임스페이스/파드 → 노드, 만든 주체)
        Map<String, PodStatusDto> pods = new LinkedHashMap<>();
        query("kube_pod_info", s -> pods.computeIfAbsent(key(s), k -> PodStatusDto.builder()
                .namespace(s.label(NS))
                .name(s.label(POD))
                .node(s.label("node"))
                .ownerKind(blankToNull(s.label("created_by_kind")))
                .ownerName(blankToNull(s.label("created_by_name")))
                .images(new ArrayList<>())
                .build()));

        query("kube_pod_status_phase == 1", s -> with(pods, s, p -> p.setPhase(s.label("phase"))));
        query("sum by (namespace, pod) (kube_pod_container_status_ready)", s -> with(pods, s, p -> p.setReadyContainers((int) s.value())));
        query("count by (namespace, pod) (kube_pod_container_status_ready)", s -> with(pods, s, p -> p.setTotalContainers((int) s.value())));
        query("sum by (namespace, pod) (kube_pod_container_status_restarts_total)", s -> with(pods, s, p -> p.setRestarts((int) s.value())));
        query("sum by (namespace, pod) (increase(kube_pod_container_status_restarts_total[1h]))",
                s -> with(pods, s, p -> p.setRestartsLastHour((int) Math.round(s.value()))));
        query("kube_pod_container_status_waiting_reason == 1", s -> with(pods, s, p -> p.setWaitingReason(s.label("reason"))));
        query("kube_pod_container_status_last_terminated_reason == 1", s -> with(pods, s, p -> p.setLastTerminatedReason(s.label("reason"))));
        query("kube_pod_created", s -> with(pods, s, p -> p.setCreatedAt((long) s.value())));

        // 이미지 (컨테이너 여러 개면 전부, 중복 제거·정렬)
        Map<String, TreeSet<String>> images = new HashMap<>();
        query("kube_pod_container_info", s -> images.computeIfAbsent(key(s), k -> new TreeSet<>()).add(s.label("image")));
        images.forEach((k, set) -> {
            PodStatusDto p = pods.get(k);
            if (p != null) p.setImages(new ArrayList<>(set));
        });

        // 리소스 사용량·한도 (이 클러스터 cAdvisor 는 파드 단위 한 줄만 내보냄 — container 라벨 없음)
        query("sum by (namespace, pod) (rate(container_cpu_usage_seconds_total{pod!=\"\"}[2m]))",
                s -> with(pods, s, p -> p.setCpuCores(s.value())));
        query("sum by (namespace, pod) (container_memory_working_set_bytes{pod!=\"\"})",
                s -> with(pods, s, p -> p.setMemoryMB(Math.round(s.value() / BYTES_PER_MB))));
        query("sum by (namespace, pod) (kube_pod_container_resource_limits{resource=\"cpu\"})",
                s -> with(pods, s, p -> p.setCpuLimitCores(s.value())));
        query("sum by (namespace, pod) (kube_pod_container_resource_limits{resource=\"memory\"})",
                s -> with(pods, s, p -> p.setMemoryLimitMB(Math.round(s.value() / BYTES_PER_MB))));

        List<PodStatusDto> result = new ArrayList<>(pods.values());
        result.sort(Comparator.comparing(PodStatusDto::getNamespace).thenComparing(PodStatusDto::getName));
        return result;
    }

    /** 쿼리 1개 실행 — 실패하면 로그만 남기고 그 정보만 비움 */
    private void query(String promql, Consumer<Sample> consumer) {
        try {
            prometheus.querySamples(promql).forEach(consumer);
        } catch (RuntimeException e) { // WebClient·타임아웃·응답 파싱 오류 (Reactor 가 RuntimeException 으로 감쌈)
            log.debug("파드 상태 쿼리 실패 [{}]: {}", promql, e.getMessage());
        }
    }

    private static void with(Map<String, PodStatusDto> pods, Sample s, Consumer<PodStatusDto> setter) {
        PodStatusDto p = pods.get(key(s));
        if (p != null) setter.accept(p);
    }

    private static String key(Sample s) {
        return s.label(NS) + "/" + s.label(POD);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }
}
