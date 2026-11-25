package com.finance.dart.monitoring.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourceMetricsDto {

    private List<ContainerMetric> containers;
    private GpuMetric gpu;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContainerMetric {
        private String name;
        private double cpuPercent;
        private double cpuLimitCores;
        private long memoryMB;
        private long memoryLimitMB;
        /** 컨테이너 시작 시각 (epoch 초, 재시작하면 갱신, 모르면 0) — 화면 가동시간 계산용 */
        private long startedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GpuMetric {
        private double utilPercent;
        private long memUsedMB;
        private long memTotalMB;
    }
}
