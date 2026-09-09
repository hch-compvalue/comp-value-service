package com.finance.dart.api.common.dto.evaluation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 수익률 추적 집계 결과 (2-4)
 * - 특정 기준일의 투자판정/가치등급 그룹이 최신 시점까지 낸 평균 수익률·승률
 * - 수익률 통계는 표본이 0건일 때 null (0%와 "데이터 없음"을 구분하기 위해 래퍼 타입 사용)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PerformanceSummaryDto {

    /** 그룹 키 (투자판정 또는 가치등급 값) */
    private String group;

    /** 종목 수 (기준일·최신일 모두 가격이 있고, 이상치로 제외되지 않은 종목) */
    private int count;

    /** 평균 수익률 (%) */
    private Double avgReturnPct;

    /** 중앙값 수익률 (%) - 소수 종목의 극단값에 휘둘리지 않는 대표값 */
    private Double medianReturnPct;

    /** 승률 (수익률 > 0 비율, %) */
    private Double winRatePct;

    /** 최고 수익률 (%) */
    private Double maxReturnPct;

    /** 최저 수익률 (%) */
    private Double minReturnPct;

    /** 코퍼레이트 액션(액면분할·주식병합) 의심으로 집계에서 제외된 종목 수 */
    private int excludedCount;

    /**
     * 최신일 스냅샷에 없어 수익률을 산출하지 못한 종목 수 (기준일 이후 추천 목록 이탈·평가 실패 등)
     * 저평가 조건을 벗어날 만큼 주가가 오른 종목이 우선적으로 빠지므로, 이 값이 크면 집계가 실제보다 보수적일 수 있다.
     */
    private int unmatchedCount;

    /** 기준일 스냅샷의 가격이 없거나 0 이하여서 기준가를 잡지 못한 종목 수 */
    private int noBasePriceCount;
}
