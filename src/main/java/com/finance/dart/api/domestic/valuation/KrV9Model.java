package com.finance.dart.api.domestic.valuation;

import com.finance.dart.api.common.constants.ValuationConstants;
import com.finance.dart.api.common.dto.CompanySharePriceCalculator;
import com.finance.dart.api.common.dto.CompanySharePriceResultDetail;
import com.finance.dart.api.common.service.PerShareValueCalculationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 국내 V9 = 미국과 같은 V9 계산식 + 국내 상수(세율 26.4% 등, ValuationConstants.KR)
 */
@Component
@RequiredArgsConstructor
public class KrV9Model implements KrValuationModel {

    public static final String VERSION = "V9";

    private final PerShareValueCalculationService calculationService;

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public String calculate(CompanySharePriceCalculator req, CompanySharePriceResultDetail detail, String sector) {
        return calculationService.calPerValueV9(req, detail, sector, ValuationConstants.KR);
    }
}
