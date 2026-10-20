package com.finance.dart.monitoring.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 클러스터 파드 1개의 상태 (Prometheus kube-state-metrics + cAdvisor 기반)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PodStatusDto {

    private String namespace;
    private String name;
    /** Pending / Running / Succeeded / Failed / Unknown */
    private String phase;
    private int readyContainers;
    private int totalContainers;
    private int restarts;
    /** 최근 1시간 재시작 횟수 (누적값은 PC 재부팅마다 늘어 의미가 약함) */
    private int restartsLastHour;
    /** 대기 사유 (CrashLoopBackOff, ImagePullBackOff 등, 없으면 null) */
    private String waitingReason;
    /** 직전 종료 사유 (OOMKilled, Error, Completed 등, 없으면 null) */
    private String lastTerminatedReason;
    private String node;
    /** 만든 주체 (ReplicaSet, StatefulSet, DaemonSet, Job …) */
    private String ownerKind;
    private String ownerName;
    /** 생성 시각 (epoch 초, 없으면 0) */
    private long createdAt;
    private List<String> images;
    private double cpuCores;
    private double cpuLimitCores;
    private long memoryMB;
    private long memoryLimitMB;
}
