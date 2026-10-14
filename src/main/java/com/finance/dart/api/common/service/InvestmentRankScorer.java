package com.finance.dart.api.common.service;

import com.finance.dart.api.common.entity.EvaluationHistoryEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 투자 순위 점수 (2026-10-14)
 * - 그날 평가 후보 안에서 세 요소의 백분위를 구해 평균: (수익성 ROE + 가치점수 + 단기 눌림) / 3
 * - 단기 눌림 = 최근 1주 수익률이 낮을수록 높은 백분위 (우량·저평가 종목의 일시적 하락이 2~4주 뒤 되돌아오는 경향)
 * - 근거: 스윙(2~4주) 백테스트에서 순위 예측력이 확인된 요소만 사용, 가중치 학습 없음(동일가중)
 *   개발기간 1,196종목 4주 IC +0.044(t3.9, 7/7년), 봉인기간(2024-10~2026-09) IC +0.020 · 상위−하위 +0.65%p
 * - 백분위 정의는 백테스트(pandas rank(pct=True))와 동일: 값이 있는 종목 안에서 평균순위 / 개수
 */
public final class InvestmentRankScorer {

    private static final double PERCENT = 100.0;
    private static final double ONE_DECIMAL = 10.0;
    private static final int COMPONENT_COUNT = 3;

    private InvestmentRankScorer() {}

    /**
     * 후보 전체에 백분위·순위 점수를 채운다 (roe, return1wPct, valueScore 는 미리 채워져 있어야 함)
     * - 요소 값이 없는 종목은 그 요소 백분위가 null, 순위 점수도 null
     */
    public static void assign(List<EvaluationHistoryEntity> candidates) {
        Map<EvaluationHistoryEntity, Double> roe = percentiles(candidates, EvaluationHistoryEntity::getRoe);
        Map<EvaluationHistoryEntity, Double> value = percentiles(candidates, EvaluationHistoryEntity::getValueScore);
        Map<EvaluationHistoryEntity, Double> ret1w = percentiles(candidates, EvaluationHistoryEntity::getReturn1wPct);

        for (EvaluationHistoryEntity e : candidates) {
            Double roePct = roe.get(e), valuePct = value.get(e);
            Double pullbackPct = ret1w.containsKey(e) ? 1.0 - ret1w.get(e) : null;
            e.setRoePct(toScore(roePct));
            e.setValuePct(toScore(valuePct));
            e.setPullbackPct(toScore(pullbackPct));
            e.setRankScore(roePct == null || valuePct == null || pullbackPct == null
                    ? null : toScore((roePct + valuePct + pullbackPct) / COMPONENT_COUNT));
        }
    }

    /**
     * 값이 있는 종목만 대상으로 백분위 (0~1]: 오름차순 평균 순위 / 개수. 동점은 같은 평균 순위
     */
    static Map<EvaluationHistoryEntity, Double> percentiles(List<EvaluationHistoryEntity> rows,
                                                            Function<EvaluationHistoryEntity, Double> valueFn) {
        List<EvaluationHistoryEntity> valid = new ArrayList<>();
        for (EvaluationHistoryEntity e : rows) {
            Double v = valueFn.apply(e);
            if (v != null && !v.isNaN() && !v.isInfinite()) valid.add(e);
        }
        valid.sort(Comparator.comparingDouble(e -> valueFn.apply(e)));

        Map<EvaluationHistoryEntity, Double> result = new IdentityHashMap<>();  // 엔티티 equals/hashCode 가 필드 기반이라 값 채우는 동안 변함 → 동일성 기준
        int n = valid.size();
        int i = 0;
        while (i < n) {
            int j = i;
            double v = valueFn.apply(valid.get(i));
            while (j + 1 < n && valueFn.apply(valid.get(j + 1)) == v) j++;
            double avgRank = (i + 1 + j + 1) / 2.0;   // 1-based 평균 순위
            for (int k = i; k <= j; k++) result.put(valid.get(k), avgRank / n);
            i = j + 1;
        }
        return result;
    }

    private static Double toScore(Double fraction) {
        return fraction == null ? null : Math.round(fraction * PERCENT * ONE_DECIMAL) / ONE_DECIMAL;
    }
}
