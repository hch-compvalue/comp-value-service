package com.finance.dart.api.service;

import com.finance.dart.api.common.context.RequestContext;
import com.finance.dart.api.common.dto.CompanySharePriceCalculator;
import com.finance.dart.api.common.service.PerShareValueCalcHelper;
import com.finance.dart.api.common.service.PerShareValueCalculationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class PerShareValueCalculationServiceTest {

    @Mock
    private RequestContext requestContext; // 의존성 목 객체

    private PerShareValueCalcHelper calcHelper;

    private PerShareValueCalculationService service;

    @BeforeEach
    void setUp() {
        calcHelper = new PerShareValueCalcHelper(requestContext);
        service = new PerShareValueCalculationService(requestContext, calcHelper);
    }


    @Test
    void testCalPerValue() {
        // 테스트용 DTO 값 설정 (예시 값)
        CompanySharePriceCalculator req = new CompanySharePriceCalculator();
        req.setOperatingProfitPrePre("100");
        req.setOperatingProfitPre("150");
        req.setOperatingProfitCurrent("200");
        req.setCurrentAssetsTotal("1000");
        req.setCurrentLiabilitiesTotal("200");
        req.setCurrentRatio("5");
        req.setInvestmentAssets("50");
        req.setFixedLiabilities("100");
        req.setIssuedShares("9999999999");
        req.setUnit("100000000");

        // 계산 메서드 실행
        String result = service.calPerValue(req);

        // 예상 결과 계산 (예시)
        // 결과: 15
        assertEquals("15", result, "한 주당 계산 결과가 예상과 달라요");
    }
}
