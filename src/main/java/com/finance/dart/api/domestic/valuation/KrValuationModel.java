package com.finance.dart.api.domestic.valuation;

import com.finance.dart.api.common.dto.CompanySharePriceCalculator;
import com.finance.dart.api.common.dto.CompanySharePriceResultDetail;

/**
 * 국내 주당가치 계산 모델 (ROADMAP_domestic 3단계 분기점)
 * - 국내만 다른 식이 필요하면 새 구현(예: V9-KR1)을 빈으로 등록하고 KrValuationService.ACTIVE_MODEL 만 바꾼다
 * - 공통 V9(PerShareValueCalculationService)는 수정하지 않는다 (버전 독립 원칙)
 */
public interface KrValuationModel {

    /** 결과·Redis 키에 기록되는 버전 */
    String version();

    /** @return 주당 적정가 */
    String calculate(CompanySharePriceCalculator req, CompanySharePriceResultDetail detail, String sector);
}
