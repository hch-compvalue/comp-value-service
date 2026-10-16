package com.finance.dart.api.abroad.service;

import com.finance.dart.api.abroad.dto.fmp.chart.StockPriceVolumeReqDto;
import com.finance.dart.api.abroad.dto.fmp.chart.StockPriceVolumeResDto;
import com.finance.dart.api.abroad.dto.fmp.company.CompanyProfileDataResDto;
import com.finance.dart.api.abroad.dto.fmp.forexquote.ForexQuoteReqDto;
import com.finance.dart.api.abroad.dto.fmp.forexquote.ForexQuoteResDto;
import com.finance.dart.api.abroad.dto.fmp.incomestatement.IncomeStatResDto;
import com.finance.dart.api.abroad.dto.fmp.incomestatgrowth.IncomeStatGrowthResDto;
import com.finance.dart.api.abroad.dto.fmp.financialratios.FinancialRatiosTTM_ResDto;
import com.finance.dart.api.abroad.service.fmp.*;
import com.finance.dart.api.common.constants.RequestContextConst;
import com.finance.dart.api.common.context.RequestContext;
import com.finance.dart.api.common.dto.CompanySharePriceCalculator;
import com.finance.dart.api.common.dto.CompanySharePriceResult;
import com.finance.dart.api.common.dto.CompanySharePriceResultDetail;
import com.finance.dart.common.util.StringUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * US_StockCalFromFpmService 공유 헬퍼
 */
@Slf4j
@AllArgsConstructor
@Component
public class US_StockCalHelper {

    private static final int TRSC_DELAY = 50;    // 0.05s

    private final RequestContext requestContext;
    private final CompanyProfileSearchService profileSearchService;
    private final ForexQuoteService forexQuoteService;
    private final StockPriceVolumeService stockPriceVolumeService;

    /**
     * 기업정보 조회
     */
    public CompanyProfileDataResDto getCompanyProfile(String symbol, CompanySharePriceResult result) {

        List<CompanyProfileDataResDto> companyList = profileSearchService.findProfileListBySymbol(symbol);
        if(companyList == null || companyList.size() != 1) return null;

        CompanyProfileDataResDto company = companyList.get(0);

        result.set기업코드(company.getCik());
        result.set기업명(company.getCompanyName());
        result.set주식코드(company.getSymbol());

        return company;
    }

    /**
     * 영업이익 정보 세팅
     */
    public void setIncomeStat(CompanySharePriceCalculator calParam, List<IncomeStatResDto> incomeStat, CompanySharePriceResultDetail resultDetail) {

        String cIncome = StringUtil.defaultString(incomeStat.get(0).getOperatingIncome());
        String pIncome = StringUtil.defaultString(incomeStat.get(1).getOperatingIncome());
        String ppIncome = StringUtil.defaultString(incomeStat.get(2).getOperatingIncome());

        calParam.setOperatingProfitCurrent(cIncome);    // 당기
        calParam.setOperatingProfitPre(pIncome);        // 전기
        calParam.setOperatingProfitPrePre(ppIncome);    // 전전기

        resultDetail.set영업이익_당기(cIncome);
        resultDetail.set영업이익_전기(pIncome);
        resultDetail.set영업이익_전전기(ppIncome);
    }

    /**
     * 시가총액기준 매출 조회 (PSR)
     */
    public void setPsr(
            CompanySharePriceCalculator calParam,
            List<IncomeStatResDto> income,
            List<IncomeStatGrowthResDto> incomeStatGrowth,
            List<FinancialRatiosTTM_ResDto> financialRatios
    ) {

        //@ 매출액 (income 리스트에서 이미 조회됨)
        String revenueCurrent = StringUtil.defaultString(income.get(0).getRevenue());
        String revenuePre = StringUtil.defaultString(income.get(1).getRevenue());
        String revenuePrePre = StringUtil.defaultString(income.get(2).getRevenue());
        calParam.setRevenue(revenueCurrent);  // 또는 3년 평균 (지금은 당기)
        // 성장주 평가 목적으로 매출정보를 활용하기 때문에 당기만으로 계산

        //@ 매출 성장률 (incomeStatGrowth에서 조회)
        String revenueGrowth = StringUtil.defaultString(incomeStatGrowth.get(0).getGrowthRevenue());
        calParam.setRevenueGrowth(revenueGrowth);

        //@ PSR (financialRatios에서 조회)
        String psr = StringUtil.defaultString(financialRatios.get(0).getPriceToSalesRatioTTM());
        calParam.setPsr(psr);
    }

    /**
     * 연구개발비 세팅
     */
    public void setRnDStat(CompanySharePriceCalculator calParam, List<IncomeStatResDto> incomeStat, CompanySharePriceResultDetail resultDetail) {

        String cRnd = StringUtil.defaultString(incomeStat.get(0).getResearchAndDevelopmentExpenses());
        String pRnd = StringUtil.defaultString(incomeStat.get(1).getResearchAndDevelopmentExpenses());
        String ppRnd = StringUtil.defaultString(incomeStat.get(2).getResearchAndDevelopmentExpenses());

        calParam.setRndCurrent(cRnd);    // 당기
        calParam.setRndPre(pRnd);        // 전기
        calParam.setRndPrePre(ppRnd);    // 전전기

        resultDetail.set연구개발비_당기(cRnd);
        resultDetail.set연구개발비_전기(pRnd);
        resultDetail.set연구개발비_전전기(ppRnd);
    }

    /**
     * 오류 응답조립
     */
    public CompanySharePriceResult errorProcess(CompanySharePriceResult result, String message) {

        if(result == null) result = new CompanySharePriceResult();

        if(StringUtil.isStringEmpty(result.get결과메시지())) {
            result.set결과메시지(message);

        } else {
            String rstMessage = result.get결과메시지();
            result.set결과메시지(message + "[" + rstMessage + "]");
        }

        result.set정상처리여부(false);
        return result;
    }

    /**
     * 결과 상세에 컨텍스트 저장데이터 세팅
     */
    public void setRstDetailContextData(CompanySharePriceResultDetail resultDetail) {
        resultDetail.set영업이익_합계(requestContext.getAttributeAsString(RequestContextConst.영업이익_합계));
        resultDetail.set영업이익_평균(requestContext.getAttributeAsString(RequestContextConst.영업이익_평균));

        resultDetail.set계산_사업가치(StringUtil.defaultString(requestContext.getAttributeAsString(RequestContextConst.계산_사업가치), "N/A"));
        resultDetail.set계산_재산가치(StringUtil.defaultString(requestContext.getAttributeAsString(RequestContextConst.계산_재산가치), "N/A"));
        resultDetail.set계산_부채(StringUtil.defaultString(requestContext.getAttributeAsString(RequestContextConst.계산_부채), "N/A"));
        resultDetail.set계산_기업가치(StringUtil.defaultString(requestContext.getAttributeAsString(RequestContextConst.계산_기업가치), "N/A"));

        if(StringUtil.isStringEmpty(resultDetail.getPER()))
            resultDetail.setPER(requestContext.getAttributeAsString(RequestContextConst.PER));
    }

    /**
     * 달러 환율정보 조회
     */
    public double getForexQuotePriceUSD(String currency) {

        String symbol = currency + "USD";

        ForexQuoteReqDto forexQuoteReqDto = new ForexQuoteReqDto(symbol);
        List<ForexQuoteResDto> resList = forexQuoteService.findForexQuote(forexQuoteReqDto);

        if(resList == null || resList.size() == 0) return -1;

        return resList.get(0).getPrice();
    }

    /**
     * 3년 내 최고가 조회
     */
    public Double get3YearsHistoricalHighPrice(String symbol) {
        try {
            // 3년 전 날짜 계산
            LocalDate today = LocalDate.now();
            LocalDate threeYearsAgo = today.minusYears(3);

            StockPriceVolumeReqDto reqDto = new StockPriceVolumeReqDto(
                symbol,
                threeYearsAgo.format(DateTimeFormatter.ISO_LOCAL_DATE),
                today.format(DateTimeFormatter.ISO_LOCAL_DATE)
            );

            Thread.sleep(TRSC_DELAY);
            List<StockPriceVolumeResDto> priceHistory = stockPriceVolumeService.findStockPriceVolume(reqDto);

            // 데이터 없으면 null 반환 (신생기업의 경우 3년 데이터 없을 수 있음)
            if (priceHistory == null || priceHistory.isEmpty()) {
                if(log.isDebugEnabled()) log.debug("[3년내 최고가] {} - 데이터 없음 (신생기업 가능성)", symbol);
                return null;
            }

            // 최고가 추출
            Double highPrice = priceHistory.stream()
                .map(StockPriceVolumeResDto::getHigh)
                .filter(h -> h != null)
                .max(Double::compare)
                .orElse(null);

            if(log.isDebugEnabled()) log.debug("[3년내 최고가] {} - ${}", symbol, highPrice);
            return highPrice;

        } catch (Exception e) {
            log.warn("[3년내 최고가] {} - 조회 실패: {}", symbol, e.getMessage());
            return null;
        }
    }

    /**
     * 52주(1년) 내 최고가 조회
     */
    public Double get52WeekHighPrice(String symbol) {
        try {
            LocalDate today = LocalDate.now();
            LocalDate oneYearAgo = today.minusYears(1);

            StockPriceVolumeReqDto reqDto = new StockPriceVolumeReqDto(
                symbol,
                oneYearAgo.format(DateTimeFormatter.ISO_LOCAL_DATE),
                today.format(DateTimeFormatter.ISO_LOCAL_DATE)
            );

            Thread.sleep(TRSC_DELAY);
            List<StockPriceVolumeResDto> priceHistory = stockPriceVolumeService.findStockPriceVolume(reqDto);

            if (priceHistory == null || priceHistory.isEmpty()) {
                if(log.isDebugEnabled()) log.debug("[52주 최고가] {} - 데이터 없음", symbol);
                return null;
            }

            Double highPrice = priceHistory.stream()
                .map(StockPriceVolumeResDto::getHigh)
                .filter(h -> h != null)
                .max(Double::compare)
                .orElse(null);

            if(log.isDebugEnabled()) log.debug("[52주 최고가] {} - ${}", symbol, highPrice);
            return highPrice;

        } catch (Exception e) {
            log.warn("[52주 최고가] {} - 조회 실패: {}", symbol, e.getMessage());
            return null;
        }
    }
}
