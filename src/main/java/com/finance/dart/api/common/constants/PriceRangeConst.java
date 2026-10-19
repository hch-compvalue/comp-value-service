package com.finance.dart.api.common.constants;

import java.util.List;

/**
 * 20거래일 예상 변동 범위 (투자 판단 상세 모달 '진입 타이밍 분석')
 *
 * <p>2026-10-19 검증: 볼린저밴드·최근 고저·변곡점·이동평균 기반 지지/저항선은 '같은 ATR 거리의 아무 가격선'보다
 * 더 잘 버티거나 반등하지 않았다(미국 1,494종목, 2018~2026 주간 61만 관측, 개발·확인 구간 모두).
 * 반면 '현재가 ± k·ATR14'의 20거래일 내 종가 이탈 확률은 두 구간에서 안정적이었다 → 확률과 함께 범위로 표시한다.
 * 근거: compvalue/test/backtest-sr-2026-10/README.md
 */
public final class PriceRangeConst {

    private PriceRangeConst() {
    }

    /** 범위 기간 (거래일) */
    public static final int RANGE_DAYS = 20;

    /** 표시 범위 = 현재가 ± RANGE_ATR_MULTIPLE × ATR14 */
    public static final double RANGE_ATR_MULTIPLE = 2.0;

    /** ATR 거리별 20거래일 내 종가 이탈(아래)·돌파(위) 확률 — 두 구간 평균 */
    public static final List<BreakProb> BREAK_PROB_TABLE = List.of(
            new BreakProb(1.0, 0.56, 0.61),
            new BreakProb(1.5, 0.45, 0.50),
            new BreakProb(2.0, 0.36, 0.40),
            new BreakProb(3.0, 0.22, 0.26)
    );

    /**
     * @param atrMultiple 현재가로부터 ATR 배수
     * @param down        그 가격 아래로 종가 이탈 확률
     * @param up          그 가격 위로 종가 돌파 확률
     */
    public record BreakProb(double atrMultiple, double down, double up) {
    }

    /** 표시 범위(RANGE_ATR_MULTIPLE)에 해당하는 확률 */
    public static BreakProb rangeProb() {
        return BREAK_PROB_TABLE.stream().filter(p -> p.atrMultiple() == RANGE_ATR_MULTIPLE).findFirst()
                .orElseThrow();
    }
}
