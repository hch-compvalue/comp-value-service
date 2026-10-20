package com.finance.dart.monitoring.service;

import com.finance.dart.monitoring.dto.ResourceMetricsDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.util.retry.Retry;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Prometheus HTTP API를 통해 컨테이너/GPU 리소스 메트릭 수집
 */
@Slf4j
@Service
public class PrometheusQueryService {

    private static final int QUERY_TIMEOUT_SECONDS = 5;
    private static final int QUERY_RETRY_COUNT = 1;
    private static final long QUERY_RETRY_BACKOFF_MS = 300;

    private final WebClient webClient;
    private final String prometheusUrl;
    private final boolean enabled;
    private final String namespace;

    public PrometheusQueryService(
            WebClient webClient,
            @Value("${monitoring.prometheus.url:http://prometheus.monitoring.svc.cluster.local:9090}") String prometheusUrl,
            @Value("${monitoring.prometheus.enabled:false}") boolean enabled,
            @Value("${monitoring.prometheus.namespace:comp-value}") String namespace
    ) {
        this.webClient = webClient;
        this.prometheusUrl = prometheusUrl;
        this.enabled = enabled;
        this.namespace = namespace;
    }

    public ResourceMetricsDto queryMetrics() {
        if (!enabled) {
            return ResourceMetricsDto.builder()
                    .containers(List.of())
                    .gpu(null)
                    .build();
        }

        // 각 쿼리를 개별 try-catch — 하나가 타임아웃/실패해도 나머지는 살림
        Map<String, Double> cpuMap = Map.of();
        try {
            cpuMap = queryVector(
                    "sum by (pod) (rate(container_cpu_usage_seconds_total{namespace=\"" + namespace + "\",pod!=\"\"}[1m])) * 100",
                    "pod"
            );
        } catch (Exception e) {
            log.debug("Prometheus CPU 메트릭 조회 실패: {}", e.getMessage());
        }

        Map<String, Double> memMap = Map.of();
        try {
            memMap = queryVector(
                    "sum by (pod) (container_memory_working_set_bytes{namespace=\"" + namespace + "\",pod!=\"\"})",
                    "pod"
            );
        } catch (Exception e) {
            log.debug("Prometheus Memory 메트릭 조회 실패: {}", e.getMessage());
        }

        Map<String, Double> memLimitMap = Map.of();
        try {
            memLimitMap = queryVector(
                    "sum by (pod) (kube_pod_container_resource_limits{namespace=\"" + namespace + "\",resource=\"memory\",pod!=\"\"})",
                    "pod"
            );
        } catch (Exception e) {
            log.debug("Prometheus Memory Limit 메트릭 조회 실패: {}", e.getMessage());
        }

        Map<String, Double> cpuLimitMap = Map.of();
        try {
            cpuLimitMap = queryVector(
                    "sum by (pod) (kube_pod_container_resource_limits{namespace=\"" + namespace + "\",resource=\"cpu\",pod!=\"\"})",
                    "pod"
            );
        } catch (Exception e) {
            log.debug("Prometheus CPU Limit 메트릭 조회 실패: {}", e.getMessage());
        }

        // Running 상태 pod만 필터링 — 종료된 pod 찌꺼기 제거
        Set<String> runningPods = Set.of();
        try {
            Map<String, Double> phaseMap = queryVector(
                    "kube_pod_status_phase{namespace=\"" + namespace + "\",phase=\"Running\"} == 1",
                    "pod"
            );
            runningPods = phaseMap.keySet();
        } catch (Exception e) {
            log.debug("Prometheus Running pods 조회 실패: {}", e.getMessage());
        }

        // 3개 쿼리 결과의 union → Running pod만 남기기
        Set<String> allPods = new LinkedHashSet<>();
        allPods.addAll(memLimitMap.keySet());
        allPods.addAll(cpuLimitMap.keySet());
        allPods.addAll(memMap.keySet());
        allPods.addAll(cpuMap.keySet());
        if (!runningPods.isEmpty()) {
            allPods.retainAll(runningPods);
        }

        List<ResourceMetricsDto.ContainerMetric> containers = new ArrayList<>();
        for (String pod : allPods) {
            String name = podToServiceName(pod);
            containers.add(ResourceMetricsDto.ContainerMetric.builder()
                    .name(name)
                    .cpuPercent(cpuMap.getOrDefault(pod, 0.0))
                    .cpuLimitCores(cpuLimitMap.getOrDefault(pod, 0.0))
                    .memoryMB(Math.round(memMap.getOrDefault(pod, 0.0) / 1024 / 1024))
                    .memoryLimitMB(Math.round(memLimitMap.getOrDefault(pod, 0.0) / 1024 / 1024))
                    .build());
        }

        // GPU 메트릭
        ResourceMetricsDto.GpuMetric gpuMetric = null;
        try {
            Map<String, Double> gpuUtil = queryVector("DCGM_FI_DEV_GPU_UTIL", "gpu");
            Map<String, Double> gpuMem = queryVector("DCGM_FI_DEV_FB_USED", "gpu");
            Map<String, Double> gpuMemTotal = queryVector("DCGM_FI_DEV_FB_FREE", "gpu");

            if (!gpuUtil.isEmpty()) {
                double util = gpuUtil.values().iterator().next();
                long memUsed = Math.round(gpuMem.values().stream().findFirst().orElse(0.0));
                long memFree = Math.round(gpuMemTotal.values().stream().findFirst().orElse(0.0));

                gpuMetric = ResourceMetricsDto.GpuMetric.builder()
                        .utilPercent(util)
                        .memUsedMB(memUsed)
                        .memTotalMB(memUsed + memFree)
                        .build();
            }
        } catch (Exception e) {
            log.debug("Prometheus GPU 메트릭 조회 실패: {}", e.getMessage());
        }

        return ResourceMetricsDto.builder()
                .containers(containers)
                .gpu(gpuMetric)
                .build();
    }

    /** Prometheus 수집 사용 여부 (로컬 등 비활성 환경에서는 false) */
    public boolean isEnabled() {
        return enabled;
    }

    /** 라벨 묶음 + 값 한 건 */
    public record Sample(Map<String, String> labels, double value) {
        public String label(String key) {
            return labels.getOrDefault(key, "");
        }
    }

    /**
     * instant 쿼리 결과를 라벨 전체와 함께 반환 (여러 라벨로 묶어야 하는 파드 상태 조회용)
     * - 5초 타임아웃, 실패 시 1회 재시도
     */
    @SuppressWarnings("unchecked")
    public List<Sample> querySamples(String query) {
        URI uri = UriComponentsBuilder.fromHttpUrl(prometheusUrl)
                .path("/api/v1/query")
                .queryParam("query", query)
                .build()
                .encode()
                .toUri();

        Map<String, Object> response = webClient.get()
                .uri(uri)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(QUERY_TIMEOUT_SECONDS))
                .retryWhen(Retry.backoff(QUERY_RETRY_COUNT, Duration.ofMillis(QUERY_RETRY_BACKOFF_MS)))
                .block();

        if (response == null || !(response.get("data") instanceof Map<?, ?> data)) return List.of();
        if (!(data.get("result") instanceof List<?> results)) return List.of();

        List<Sample> samples = new ArrayList<>();
        for (Object item : results) {
            Map<String, Object> row = (Map<String, Object>) item;
            Map<String, String> metric = (Map<String, String>) row.get("metric");
            List<Object> value = (List<Object>) row.get("value");
            if (metric == null || value == null || value.size() < 2) continue;
            samples.add(new Sample(Collections.unmodifiableMap(metric), Double.parseDouble(String.valueOf(value.get(1)))));
        }
        return samples;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Double> queryVector(String query, String labelKey) {
        URI uri = UriComponentsBuilder.fromHttpUrl(prometheusUrl)
                .path("/api/v1/query")
                .queryParam("query", query)
                .build()
                .encode()
                .toUri();

        Map<String, Object> response = webClient.get()
                .uri(uri)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(5))
                .block();

        Map<String, Double> result = new java.util.LinkedHashMap<>();
        if (response == null) return result;

        Map<String, Object> data = (Map<String, Object>) response.get("data");
        if (data == null) return result;

        List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");
        if (results == null) return result;

        for (Map<String, Object> item : results) {
            Map<String, String> metric = (Map<String, String>) item.get("metric");
            List<Object> value = (List<Object>) item.get("value");

            String key = metric.getOrDefault(labelKey, "unknown");
            if (key.isEmpty()) continue;
            double val = Double.parseDouble(String.valueOf(value.get(1)));
            result.put(key, val);
        }

        return result;
    }

    /**
     * pod 이름에서 서비스명 추출 (deployment replicaset 접미사 제거)
     * e.g. "comp-value-service-5845fd94fd-d2wvm" → "comp-value-service"
     */
    private String podToServiceName(String pod) {
        // ReplicaSet 패턴: {name}-{replicaset-hash}-{pod-hash}
        String[] parts = pod.split("-");
        if (parts.length >= 3) {
            // 끝 2개 (replicaset hash + pod hash) 제거
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length - 2; i++) {
                if (i > 0) sb.append("-");
                sb.append(parts[i]);
            }
            return sb.toString();
        }
        return pod;
    }
}
