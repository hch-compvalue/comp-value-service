package com.finance.dart.api.common.constants;

import java.math.BigDecimal;

/**
 * 시장별 V9 계산 상수 (docs/ROADMAP_domestic.md 3단계)
 * - US: 2026-10-13 V9 값 그대로 (변경 시 미국 결과가 바뀐다)
 * - KR: 세율 기본값 26.4%(법인세 최고구간 + 지방세), 정상금리 6% — 10-27 사용자 확정
 *       금융주 무위험수익률·시장위험프리미엄은 국내 값을 따로 두되 초기값은 미국과 같음 (10-30 확정, 6단계 백테스트에서 보정)
 * - riskFreeRate·marketRiskPremium 이 null 이면 섹터 파라미터(SectorParameterFactory) 값을 쓴다 (US = 기존 동작)
 * - minCoeBeta: 금융 PBR 경로 요구수익률(COE)에 쓰는 베타 하한. null 이면 하한 없음(US = 기존 동작)
 *   KR 1.0 (11-02 사용자 확정): 국내 은행 베타가 KOSPI(반도체 비중 큼) 대비 매우 낮아(KB금융 0.32) COE 6%대 → 적정 PBR 과대,
 *   10-30 전 종목 검증에서 금융 괴리 중앙값 +130% (미국 −24%)
 */
public record ValuationConstants(Market market, BigDecimal defaultTaxRate, BigDecimal maxTaxRate,
                                 BigDecimal normalizedDebtRate, BigDecimal riskFreeRate, BigDecimal marketRiskPremium,
                                 BigDecimal minCoeBeta) {

    public static final ValuationConstants US = new ValuationConstants(Market.US,
            new BigDecimal("0.21"), new BigDecimal("0.35"), new BigDecimal("0.06"), null, null, null);

    public static final ValuationConstants KR = new ValuationConstants(Market.KR,
            new BigDecimal("0.264"), new BigDecimal("0.35"), new BigDecimal("0.06"),
            new BigDecimal("0.045"), new BigDecimal("0.055"), BigDecimal.ONE);
}
