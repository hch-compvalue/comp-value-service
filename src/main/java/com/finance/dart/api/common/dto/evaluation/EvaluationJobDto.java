package com.finance.dart.api.common.dto.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 일괄 평가 작업 (서버 백그라운드 실행) 상태 + 결과
 * - results: resultsFrom 번째부터의 평가 결과 (화면은 받은 개수만큼 이어 붙임)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationJobDto {

    private String jobId;
    /** RUNNING / DONE / CANCELLED / INTERRUPTED(서버 재시작으로 끊김) / FAILED */
    private String status;
    private int total;
    private int done;
    private String startedAt;
    private String finishedAt;
    private String message;

    private int resultsFrom;
    private List<JsonNode> results;
}
