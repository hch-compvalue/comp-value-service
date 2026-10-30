package com.finance.dart.api.common.constants;

import java.math.BigDecimal;

/**
 * 시장별 V9 계산 상수 (docs/ROADMAP_domestic.md 3단계)
 * - US: 2026-10-13 V9 값 그대로 (변경 시 미국 결과가 바뀐다)
 * - KR: 세율 기본값 26.4%(법인세 최고구간 + 지방세), 정상금리 6% — 10-27 사용자 확정
 *       금융주 무위험수익률·시장위험프리미엄은 국내 값을 따로 두되 초기값은 미국과 같음 (10-30 확정, 6단계 백테스트에서 보정)
 * - riskFreeRate·marketRiskPremium 이 null 이면 섹터 파라미터(SectorParameterFactory) 값을 쓴다 (US = 기존 동작)
 */
public record ValuationConstants(Market market, BigDecimal defaultTaxRate, BigDecimal maxTaxRate,
                                 BigDecimal normalizedDebtRate, BigDecimal riskFreeRate, BigDecimal marketRiskPremium) {

    public static final ValuationConstants US = new ValuationConstants(Market.US,
            new BigDecimal("0.21"), new BigDecimal("0.35"), new BigDecimal("0.06"), null, null);

    public static final ValuationConstants KR = new ValuationConstants(Market.KR,
            new BigDecimal("0.264"), new BigDecimal("0.35"), new BigDecimal("0.06"),
            new BigDecimal("0.045"), new BigDecimal("0.055"));
}
