package com.finance.dart.api.abroad.service;

import com.finance.dart.api.abroad.consts.CurrencyConst;
import com.finance.dart.api.abroad.consts.FmpPeriod;
import com.finance.dart.api.abroad.dto.fmp.balancesheet.BalanceSheetReqDto;
import com.finance.dart.api.abroad.dto.fmp.balancesheet.BalanceSheetResDto;
import com.finance.dart.api.abroad.dto.fmp.chart.StockPriceVolumeReqDto;
import com.finance.dart.api.abroad.dto.fmp.chart.StockPriceVolumeResDto;
import com.finance.dart.api.abroad.dto.fmp.company.CompanyProfileDataResDto;
import com.finance.dart.api.abroad.dto.fmp.enterprisevalues.EnterpriseValuesReqDto;
import com.finance.dart.api.abroad.dto.fmp.enterprisevalues.EnterpriseValuesResDto;
import com.finance.dart.api.abroad.dto.fmp.financialgrowth.FinancialGrowthReqDto;
import com.finance.dart.api.abroad.dto.fmp.financialgrowth.FinancialGrowthResDto;
import com.finance.dart.api.abroad.dto.fmp.financialratios.FinancialRatiosReqDto;
import com.finance.dart.api.abroad.dto.fmp.financialratios.FinancialRatiosResDto;
import com.finance.dart.api.abroad.dto.fmp.financialratios.FinancialRatiosTTM_ReqDto;
import com.finance.dart.api.abroad.dto.fmp.financialratios.FinancialRatiosTTM_ResDto;
import com.finance.dart.api.abroad.dto.fmp.incomestatement.IncomeStatReqDto;
import com.finance.dart.api.abroad.dto.fmp.incomestatement.IncomeStatResDto;
import com.finance.dart.api.abroad.dto.fmp.incomestatgrowth.IncomeStatGrowthReqDto;
import com.finance.dart.api.abroad.dto.fmp.incomestatgrowth.IncomeStatGrowthResDto;
import com.finance.dart.api.abroad.dto.fmp.quote.AfterTradeReqDto;
import com.finance.dart.api.abroad.dto.fmp.quote.AfterTradeResDto;
import com.finance.dart.api.abroad.dto.fmp.quote.StockQuoteReqDto;
import com.finance.dart.api.abroad.dto.fmp.quote.StockQuoteResDto;
import com.finance.dart.api.abroad.service.fmp.*;
import com.finance.dart.api.common.dto.CompanySharePriceCalculator;
import com.finance.dart.api.common.dto.CompanySharePriceResult;
import com.finance.dart.api.common.dto.CompanySharePriceResultDetail;
import com.finance.dart.api.common.dto.SectorCalculationParameters;
import com.finance.dart.api.common.service.PerShareValueCalcHelper;
import com.finance.dart.api.common.service.PerShareValueCalculationService;
import com.finance.dart.api.common.service.SectorParameterFactory;
import com.finance.dart.common.component.RedisComponent;
import com.finance.dart.common.component.RedisKeyGenerator;
import com.finance.dart.common.exception.BizException;
import com.finance.dart.common.util.CalUtil;
import com.finance.dart.common.util.DateUtil;
import com.finance.dart.common.util.StringUtil;
import com.google.gson.Gson;
import com.finance.dart.api.abroad.component.FmpRateLimiter;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 해외기업 주식가치 계산 서비스 (V8 전용)
 * V1~V7은 legacy/US_StockCalLegacyService 참조
 */
@Slf4j
@AllArgsConstructor
@Service
public class US_StockCalFromFpmService {

    private final int TRSC_DELAY = 50;    // 0.05s

    private final RedisComponent redisComponent;

    private final IncomeStatementService incomeStatementService;
    private final BalanceSheetStatementService balanceSheetStatementService;
    private final EnterpriseValueService enterpriseValueService;
    private final FinancialRatiosService financialRatiosService;
    private final FinancialGrowthService financialGrowthService;
    private final IncomeStatGrowthService incomeStatGrowthService;
    private final StockQuoteService stockQuoteService;
    private final AfterTradeService afterTradeService;
    private final StockPriceVolumeService stockPriceVolumeService;

    private final PerShareValueCalculationService sharePriceCalculatorService;
    private final PerShareValueCalcHelper calcHelper;
    private final US_StockCalHelper helper;
    private final FmpRateLimiter fmpRateLimiter;


    /**
     * 주당 가치 계산
     */
    public CompanySharePriceResult calPerValue(String symbol) throws Exception {
        return calPerValueV9(symbol);
    }

    /**
     * 주당 가치 계산(다건)
     */
    public List<CompanySharePriceResult> calPerValueList(String symbols, String detail) throws Exception {
        return calPerValueListV9(symbols, detail);
    }


    // ==============================================================
    // V8: 모멘텀 필터 + 적정가 보수화 + 동적 안전마진
    // ==============================================================

    /**
     * 계산정보 조회(V8) - V7 + StockQuote 추가
     */
    private CompanySharePriceCalculator getCalParamDataV8(String symbol, CompanySharePriceResult result,
                                                           CompanySharePriceResultDetail resultDetail,
                                                           StockQuoteResDto stockQuote,
                                                           List<StockPriceVolumeResDto> priceHistory,
                                                           String sector)
            throws InterruptedException {

        if(log.isDebugEnabled()) log.debug("[V8 계산 로그] [{}] {} 계산 시작", symbol, result.get기업명());

        CompanySharePriceCalculator calParam = new CompanySharePriceCalculator();

        //@ 필요데이터 조회 (연간 영업이익 3년)
        // Thread.sleep(TRSC_DELAY); // FmpRateLimiter로 대체 (2026.03.10)
        IncomeStatReqDto incomeStatReqDto = new IncomeStatReqDto(symbol, 3, FmpPeriod.annual);
        List<IncomeStatResDto> income = incomeStatementService.findIncomeStat(incomeStatReqDto);
        if(income == null || income.size() < 3) {
            result.set결과메시지("영업이익 조회에 실패했습니다.");
            return null;
        } else {
            for(IncomeStatResDto ic : income) {
                if(!ic.getReportedCurrency().equals(CurrencyConst.USA)) {
                    double rate = helper.getForexQuotePriceUSD(ic.getReportedCurrency());
                    if(rate != -1) ic.applyExchangeRate(rate);
                }
            }
        }

        //@ 분기 영업이익 4개 조회
        // Thread.sleep(TRSC_DELAY); // FmpRateLimiter로 대체 (2026.03.10)
        IncomeStatReqDto quarterlyIncomeReqDto = new IncomeStatReqDto(symbol, 4, FmpPeriod.quarter);
        List<IncomeStatResDto> quarterlyIncome = incomeStatementService.findIncomeStat(quarterlyIncomeReqDto);
        if(quarterlyIncome != null && quarterlyIncome.size() >= 4) {
            for(IncomeStatResDto qi : quarterlyIncome) {
                if(!qi.getReportedCurrency().equals(CurrencyConst.USA)) {
                    double rate = helper.getForexQuotePriceUSD(qi.getReportedCurrency());
                    if(rate != -1) qi.applyExchangeRate(rate);
                }
            }
            calParam.setQuarterlyOpIncomeQ1(StringUtil.defaultString(quarterlyIncome.get(0).getOperatingIncome()));
            calParam.setQuarterlyOpIncomeQ2(StringUtil.defaultString(quarterlyIncome.get(1).getOperatingIncome()));
            calParam.setQuarterlyOpIncomeQ3(StringUtil.defaultString(quarterlyIncome.get(2).getOperatingIncome()));
            calParam.setQuarterlyOpIncomeQ4(StringUtil.defaultString(quarterlyIncome.get(3).getOperatingIncome()));
        }

        // Thread.sleep(TRSC_DELAY); // FmpRateLimiter로 대체 (2026.03.10)
        BalanceSheetReqDto balanceSheetReqDto = new BalanceSheetReqDto(symbol, 1, FmpPeriod.quarter);
        List<BalanceSheetResDto> balance = balanceSheetStatementService.findBalanceSheet(balanceSheetReqDto);
        if(balance == null || balance.size() < 1) {
            result.set결과메시지("재무상태표 조회에 실패했습니다.");
            return null;
        } else {
            for(BalanceSheetResDto bs : balance) {
                if(!bs.getReportedCurrency().equals(CurrencyConst.USA)) {
                    double rate = helper.getForexQuotePriceUSD(bs.getReportedCurrency());
                    if(rate != -1) bs.applyExchangeRate(rate);
                }
            }
        }

        // Thread.sleep(TRSC_DELAY); // FmpRateLimiter로 대체 (2026.03.10)
        EnterpriseValuesReqDto enterpriseValuesReqDto = new EnterpriseValuesReqDto(symbol, 1, FmpPeriod.quarter);
        List<EnterpriseValuesResDto> enterpriseValues = enterpriseValueService.findEnterpriseValue(enterpriseValuesReqDto);
        if(enterpriseValues == null || enterpriseValues.size() < 1) {
            result.set결과메시지("기업가치 조회에 실패했습니다.");
            return null;
        }

        // Thread.sleep(TRSC_DELAY); // FmpRateLimiter로 대체 (2026.03.10)
        FinancialRatiosTTM_ReqDto financialRatiosTTM_ReqDto = new FinancialRatiosTTM_ReqDto(symbol);
        List<FinancialRatiosTTM_ResDto> financialRatios = financialRatiosService.findFinancialRatiosTTM(financialRatiosTTM_ReqDto);
        if(financialRatios == null || financialRatios.size() < 1) {
            result.set결과메시지("재무비율지표(TTM) 조회에 실패했습니다.");
            return null;
        }

        // Thread.sleep(TRSC_DELAY); // FmpRateLimiter로 대체 (2026.03.10)
        List<FinancialRatiosResDto> historicalRatios = null;
        try {
            FinancialRatiosReqDto historicalRatiosReqDto = new FinancialRatiosReqDto(symbol, 5, FmpPeriod.annual);
            historicalRatios = financialRatiosService.findFinancialRatios(historicalRatiosReqDto);
        } catch (Exception e) {
            log.warn("[V8] {} - 연간 재무비율 조회 실패 (TTM PER로 폴백): {}", symbol, e.getMessage());
        }
        // 진단 로그: API 응답 원본 확인
        if(log.isDebugEnabled()) {
            if (historicalRatios != null) {
                log.debug("[V8] {} - 연간 재무비율 {}건 조회, PER 원본: {}",
                    symbol, historicalRatios.size(),
                    historicalRatios.stream().map(r -> r.getDate() + "=" + r.getPriceToEarningsRatio()).collect(Collectors.joining(", ")));
            } else {
                log.debug("[V8] {} - 연간 재무비율 조회 결과 null", symbol);
            }
        }

        // Thread.sleep(TRSC_DELAY); // FmpRateLimiter로 대체 (2026.03.10)
        FinancialGrowthReqDto financialGrowthReqDto = new FinancialGrowthReqDto(symbol, 1, FmpPeriod.fiscalYear);
        List<FinancialGrowthResDto> financialGrowth = financialGrowthService.financialStatementsGrowth(financialGrowthReqDto);
        if(financialGrowth == null || financialGrowth.size() < 1) {
            result.set결과메시지("성장률 조회에 실패했습니다.");
            return null;
        }

        // Thread.sleep(TRSC_DELAY); // FmpRateLimiter로 대체 (2026.03.10)
        IncomeStatGrowthReqDto incomeStatGrowthReqDto = new IncomeStatGrowthReqDto(symbol, 1, FmpPeriod.annual);
        List<IncomeStatGrowthResDto> incomeStatGrowth = incomeStatGrowthService.findIncomeStatGrowth(incomeStatGrowthReqDto);
        if(incomeStatGrowth == null || incomeStatGrowth.size() < 1) {
            result.set결과메시지("영업이익 성장률 조회에 실패했습니다.");
            return null;
        }

        // ----------------------------------------------------------------

        //@ 영업이익
        helper.setIncomeStat(calParam, income, resultDetail);

        //@ 매출정보
        helper.setPsr(calParam, income, incomeStatGrowth, financialRatios);

        //@ 유동자산 합계
        String assetsCurrent = StringUtil.defaultString(balance.get(0).getTotalCurrentAssets());
        calParam.setCurrentAssetsTotal(assetsCurrent);
        resultDetail.set유동자산합계(assetsCurrent);

        //@ 유동부채 합계
        String liabilitiesCurrent = StringUtil.defaultString(balance.get(0).getTotalCurrentLiabilities());
        calParam.setCurrentLiabilitiesTotal(liabilitiesCurrent);
        resultDetail.set유동부채합계(liabilitiesCurrent);

        //@ 유동비율
        String ratio;
        if("0".equals(liabilitiesCurrent) && "0".equals(assetsCurrent)) {
            ratio = "1.0";
        } else if("0".equals(liabilitiesCurrent)) {
            ratio = "2.0";
        } else if("0".equals(assetsCurrent)) {
            ratio = "0.0";
        } else {
            ratio = CalUtil.divide(assetsCurrent, liabilitiesCurrent, 2, RoundingMode.HALF_UP);
        }

        calParam.setCurrentRatio(ratio);
        resultDetail.set유동비율(ratio);

        //@ 투자자산
        String longTermInvestments = StringUtil.defaultString(balance.get(0).getLongTermInvestments());
        calParam.setInvestmentAssets(longTermInvestments);
        resultDetail.set투자자산_비유동자산내(longTermInvestments);

        //@ 무형자산 + 영업권 합산
        String intangibleAssets = StringUtil.defaultString(balance.get(0).getGoodwillAndIntangibleAssets());
        calParam.setIntangibleAssets(intangibleAssets);
        resultDetail.set무형자산(intangibleAssets);

        //@ 발행주식수
        String numberOfShares = StringUtil.defaultString(enterpriseValues.get(0).getNumberOfShares());
        calParam.setIssuedShares(numberOfShares);
        resultDetail.set발행주식수(numberOfShares);

        //@ PER (5년 평균 PER - 현재 주가 의존성 제거)
        String per;
        if (historicalRatios != null && !historicalRatios.isEmpty()) {
            List<Double> validPERs = historicalRatios.stream()
                    .map(FinancialRatiosResDto::getPriceToEarningsRatio)
                    .filter(p -> p != null && p > 0)
                    .collect(Collectors.toList());
            if (!validPERs.isEmpty()) {
                double avgPER = validPERs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                per = String.valueOf(avgPER);
                if(log.isDebugEnabled()) log.debug("[V8] {} - 5년 평균 PER: {} (데이터 {}개, 값: {})", symbol, per, validPERs.size(), validPERs);
            } else {
                per = StringUtil.defaultString(financialRatios.get(0).getPriceToEarningsRatioTTM());
                log.warn("[V8] {} - 연간 PER 데이터 없음, TTM PER({})로 폴백", symbol, per);
            }
        } else {
            per = StringUtil.defaultString(financialRatios.get(0).getPriceToEarningsRatioTTM());
            log.warn("[V8] {} - 연간 PER 데이터 없음, TTM PER({})로 폴백", symbol, per);
        }
        calParam.setPer(per);
        resultDetail.setPER(per);

        //@ PBR (그레이엄 스크리닝용)
        Double pbrTTM = financialRatios.get(0).getPriceToBookRatioTTM();
        if (pbrTTM != null) {
            resultDetail.setPBR(String.valueOf(pbrTTM));
        }

        //@ PBR 기반 평가 데이터 (Financial Services)
        // - 섹터는 프로필 조회값을 인자로 받음 (result.섹터는 계산 후에야 세팅되므로 사용 불가)
        // - BPS(ratios-TTM)는 환율 변환이 안 되므로 USD 공시 기업만 PBR 경로 허용, 그 외는 PER 경로 유지
        boolean usdReporter = CurrencyConst.USA.equals(balance.get(0).getReportedCurrency());
        if (SectorParameterFactory.getParameters(sector).isUsePbrValuation() && usdReporter) {
            Double bps = financialRatios.get(0).getBookValuePerShareTTM();
            Double netIncomePS = financialRatios.get(0).getNetIncomePerShareTTM();
            Double equityPS = financialRatios.get(0).getShareholdersEquityPerShareTTM();
            if (bps != null && bps > 0) {
                calParam.setBookValuePerShare(String.valueOf(bps));
                resultDetail.setBPS(String.valueOf(bps));
            }
            if (netIncomePS != null && equityPS != null && equityPS > 0) {
                double roe = netIncomePS / equityPS;
                calParam.setRoe(String.valueOf(roe));
                resultDetail.setROE(String.format("%.4f", roe));
            }
        }

        //@ 성장률(연간)
        String epsGrowth = StringUtil.defaultString(financialGrowth.get(0).getEpsgrowth());
        calParam.setEpsgrowth(epsGrowth);
        resultDetail.setEPS성장률(epsGrowth);

        String incomeGrowth = StringUtil.defaultString(incomeStatGrowth.get(0).getGrowthOperatingIncome());
        calParam.setOperatingIncomeGrowth(incomeGrowth);
        resultDetail.set영업이익성장률(incomeGrowth);

        //@ 최근 3년 R&D
        helper.setRnDStat(calParam, income, resultDetail);

        //@ 순부채
        Long totalDebtVal = balance.get(0).getTotalDebt();
        Long capitalLease = balance.get(0).getCapitalLeaseObligations();
        String totalDebt = StringUtil.defaultString(
                (totalDebtVal != null ? totalDebtVal : 0L) +
                        (capitalLease != null ? capitalLease : 0L)
        );

        String cash = StringUtil.defaultString(balance.get(0).getCashAndCashEquivalents());
        if(StringUtil.isStringEmpty(cash) || "0".equals(cash))
            cash = StringUtil.defaultString(balance.get(0).getCashAndShortTermInvestments());

        calParam.setTotalDebt(totalDebt);
        calParam.setCashAndCashEquivalents(cash);

        resultDetail.set총부채(totalDebt);
        resultDetail.set현금성자산(cash);

        //@ V8: SMA50, SMA200 (StockQuote에서)
        if (stockQuote != null) {
            if (stockQuote.getPriceAvg50() != null) {
                resultDetail.setSMA50(String.valueOf(stockQuote.getPriceAvg50()));
            }
            if (stockQuote.getPriceAvg200() != null) {
                resultDetail.setSMA200(String.valueOf(stockQuote.getPriceAvg200()));
            }
        }

        return calParam;
    }

    /**
     * V8: 모멘텀 필터 + 적정가 보수화 + 동적 안전마진 + 과대평가 의심 할인
     */
    public CompanySharePriceResult calPerValueV8(String symbol)
            throws Exception {

        final String VERSION = "v8";
        final String UNIT = "1";
        final String resultDataRedisKey = RedisKeyGenerator.genAbroadCompValueRstData(symbol, VERSION);

        //@ Redis 저장값 확인(캐시 역할) - 현재가격만 실시간 갱신
        String saveData = redisComponent.getValue(resultDataRedisKey);
        if(!StringUtil.isStringEmpty(saveData)) {
            CompanySharePriceResult cached = new Gson().fromJson(saveData, CompanySharePriceResult.class);
            String latestPrice = fetchLatestPrice(symbol);
            if(latestPrice != null) {
                cached.set현재가격(latestPrice);
            }
            return cached;
        }

        CompanySharePriceResult result = new CompanySharePriceResult();
        result.set기업심볼(symbol);
        CompanySharePriceResultDetail resultDetail = new CompanySharePriceResultDetail(UNIT);

        //@1. 정보 조회 ---------------------------------
        CompanyProfileDataResDto companyProfile = helper.getCompanyProfile(symbol, result);
        if(log.isDebugEnabled()) log.debug("[V8] 기업 정보 = {}", companyProfile);
        if(companyProfile == null) {
            helper.errorProcess(result, "기업 정보 조회에 실패했습니다.");
            return result;
        }

        String sector = companyProfile.getSector();
        if(log.isDebugEnabled()) log.debug("[V8] 섹터 정보: {}", sector);

        //@1-1. V8 추가: StockQuote 조회 (SMA50, SMA200, price)
        // Thread.sleep(TRSC_DELAY); // FmpRateLimiter로 대체 (2026.03.10)
        StockQuoteResDto stockQuote = null;
        try {
            StockQuoteReqDto quoteReq = new StockQuoteReqDto(symbol);
            List<StockQuoteResDto> quotes = stockQuoteService.findStockQuote(quoteReq);
            if (quotes != null && !quotes.isEmpty()) {
                stockQuote = quotes.get(0);
            }
        } catch (Exception e) {
            log.warn("[V8] {} - StockQuote 조회 실패: {}", symbol, e.getMessage());
        }

        //@1-2. 52주 가격 히스토리 조회 (RSI, 거래량 분석용)
        List<StockPriceVolumeResDto> priceHistory = null;
        try {
            LocalDate today = LocalDate.now();
            LocalDate oneYearAgo = today.minusYears(1);
            StockPriceVolumeReqDto priceReqDto = new StockPriceVolumeReqDto(
                symbol,
                oneYearAgo.format(DateTimeFormatter.ISO_LOCAL_DATE),
                today.format(DateTimeFormatter.ISO_LOCAL_DATE)
            );
            // Thread.sleep(TRSC_DELAY); // FmpRateLimiter로 대체 (2026.03.10)
            priceHistory = stockPriceVolumeService.findStockPriceVolume(priceReqDto);
        } catch (Exception e) {
            log.warn("[V8] {} - 52주 가격 히스토리 조회 실패: {}", symbol, e.getMessage());
        }

        //@2. 재무 데이터 조회
        CompanySharePriceCalculator calParam = getCalParamDataV8(symbol, result, resultDetail, stockQuote, priceHistory, sector);
        if(calParam == null) {
            helper.errorProcess(result, "재무정보 조회에 실패했습니다.");
            return result;
        }
        calParam.setUnit(UNIT);

        //@2-1. PBR 데이터
        Double pbrTTM = null;
        String pbrStr = resultDetail.getPBR();
        if (pbrStr != null && !pbrStr.isEmpty()) {
            try { pbrTTM = Double.parseDouble(pbrStr); } catch (NumberFormatException ignored) {}
        }

        //@2-2. Beta (PBR 기반 평가용)
        if (companyProfile.getBeta() != null) {
            calParam.setBeta(String.valueOf(companyProfile.getBeta()));
        }

        //@2-3. Industry (Financial Services 서브섹터 분화용)
        calParam.setIndustry(companyProfile.getIndustry());

        //@3. 계산 (V8 로직) ---------------------------------
        if(log.isDebugEnabled()) log.debug("[V8] {} - 계산 입력: PER={}, 섹터={}", symbol, calParam.getPer(), sector);
        String 계산된주당가치 = sharePriceCalculatorService.calPerValueV8(calParam, resultDetail, sector);

        //@4. 그레이엄 스크리닝 (안전마진이 통과수를 사용하므로 먼저 수행) ---------------------------------
        applyGrahamScreeningV8(symbol, sector, calParam, resultDetail, pbrTTM);

        //@5. V8: 동적 안전마진 ---------------------------------
        double totalMargin = calcHelper.calculateDynamicSafetyMargin(
                companyProfile.getBeta(), resultDetail.get그레이엄_통과수());
        resultDetail.set안전마진율(String.format("%.0f%%", totalMargin * 100));

        //@6. 매매가 산출 ---------------------------------
        String 매수적정가 = calcHelper.calculatePurchasePrice(계산된주당가치, totalMargin);
        String 목표매도가 = calcHelper.calculateSellTarget(계산된주당가치);
        String 손절매가 = calcHelper.calculateStopLoss(매수적정가);

        //@7. 결과 조립 ---------------------------------
        result.set버전(VERSION.toUpperCase());
        result.set섹터(sector);
        if(StringUtil.isStringEmpty(result.get결과메시지())) result.set결과메시지("정상 처리되었습니다.");
        result.set주당가치(계산된주당가치);
        result.set계산된주당가치(계산된주당가치);
        result.set매수적정가(매수적정가);
        result.set목표매도가(목표매도가);
        result.set손절매가(손절매가);
        result.set안전마진율(String.format("%.0f%%", totalMargin * 100));
        // 현재가: StockQuote + AftermarketTrade 비교하여 최신 가격 사용
        String latestPrice = fetchLatestPrice(symbol);
        if(latestPrice != null) {
            result.set현재가격(latestPrice);
        } else if(stockQuote != null && stockQuote.getPrice() != null) {
            result.set현재가격(String.valueOf(stockQuote.getPrice()));
        } else {
            result.set현재가격(StringUtil.defaultString(companyProfile.getPrice()));
        }
        result.set확인시간(DateUtil.getToday("yyyy-MM-dd HH:mm:ss"));
        helper.setRstDetailContextData(resultDetail);

        result.set상세정보(resultDetail);

        //@ Redis에 결과값 저장(캐시 역할)
        redisComponent.saveValueWithTtl(resultDataRedisKey, new Gson().toJson(result), 6, TimeUnit.HOURS);

        return result;
    }

    /**
     * V8: 그레이엄 스크리닝 (PER/PBR/복합/유동비율/연속흑자 5개 기준 통과수 → resultDetail)
     * - 동적 안전마진이 통과수를 사용하므로 반드시 안전마진 계산 전에 호출
     */
    private void applyGrahamScreeningV8(String symbol, String sector, CompanySharePriceCalculator calParam,
                                        CompanySharePriceResultDetail resultDetail, Double pbrTTM) {
        SectorCalculationParameters sectorParams = SectorParameterFactory.getParameters(sector);
        try {
            BigDecimal perVal = new BigDecimal(calParam.getPer());

            boolean perPass = perVal.compareTo(BigDecimal.ZERO) > 0 && perVal.compareTo(sectorParams.getMaxPER()) <= 0;
            resultDetail.set그레이엄_PER통과(perPass);

            boolean pbrPass = false;
            BigDecimal pbrVal = BigDecimal.ZERO;
            if (pbrTTM != null) {
                pbrVal = BigDecimal.valueOf(pbrTTM);
                pbrPass = pbrVal.compareTo(BigDecimal.ZERO) > 0 && pbrVal.compareTo(sectorParams.getMaxPBR()) <= 0;
            }
            resultDetail.set그레이엄_PBR통과(pbrPass);

            boolean compositePass = false;
            if (perVal.compareTo(BigDecimal.ZERO) > 0 && pbrVal.compareTo(BigDecimal.ZERO) > 0) {
                compositePass = perVal.multiply(pbrVal).compareTo(sectorParams.getMaxPERxPBR()) <= 0;
            }
            resultDetail.set그레이엄_복합통과(compositePass);

            BigDecimal currentRatioVal = new BigDecimal(calParam.getCurrentRatio());
            boolean crPass = !sectorParams.isApplyCurrentRatio() || currentRatioVal.compareTo(new BigDecimal("1.5")) >= 0;
            resultDetail.set그레이엄_유동비율통과(crPass);

            boolean profitPass = new BigDecimal(calParam.getOperatingProfitPrePre()).signum() > 0
                    && new BigDecimal(calParam.getOperatingProfitPre()).signum() > 0
                    && new BigDecimal(calParam.getOperatingProfitCurrent()).signum() > 0;
            resultDetail.set그레이엄_연속흑자통과(profitPass);

            int passCount = (perPass?1:0) + (pbrPass?1:0) + (compositePass?1:0) + (crPass?1:0) + (profitPass?1:0);
            resultDetail.set그레이엄_통과수(passCount);
            resultDetail.set그레이엄_등급(passCount >= 5 ? "강력매수" : passCount >= 4 ? "매수" : passCount >= 3 ? "관망" : "위험");
        } catch (Exception e) {
            log.warn("[V8 그레이엄 스크리닝] {} - 스크리닝 실패: {}", symbol, e.getMessage());
        }
    }

    /**
     * 주당 가치 계산(다건) V8
     */
    public List<CompanySharePriceResult> calPerValueListV8(String symbols, String detail) throws Exception {

        final int MAX_CAL_SYMBOL_SIZE = 30;

        List<CompanySharePriceResult> resultList = new LinkedList<>();

        if(symbols == null) return null;
        List<String> symbolList = Arrays.stream(symbols.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        if(symbolList.size() > MAX_CAL_SYMBOL_SIZE) {
            throw new BizException("동시 조회는 " + MAX_CAL_SYMBOL_SIZE + "건 까지만 가능합니다.");
        }

        for(String symbol : symbolList) {
            fmpRateLimiter.waitIfNeeded();  // Rate limit 종목 간 체크
            try {
                CompanySharePriceResult result = calPerValueV8(symbol);
                if("F".equals(StringUtil.defaultString(detail))) result.set상세정보(null);
                resultList.add(result);
            } catch (Exception e) {
                log.error("[V8 대량조회] {} 처리 중 오류: {}", symbol, e.getMessage());
                CompanySharePriceResult errorResult = new CompanySharePriceResult();
                errorResult.set기업심볼(symbol);
                helper.errorProcess(errorResult, "처리 중 오류가 발생했습니다.");
                resultList.add(errorResult);
            }
            // Thread.sleep(TRSC_DELAY); // FmpRateLimiter로 대체 (2026.03.10)
        }

        return resultList;
    }

    // ==============================================================
    // V9: 자기자본법 적정가 (세후이익×PER + 비영업자산) + 리스·현금 중복 제거
    // ==============================================================

    /**
     * 계산정보 조회(V9) - V8 + 이자비용/유효세율/현재가, 리스 중복차감 제거 (FMP 호출 수 V8 과 동일)
     */
    private CompanySharePriceCalculator getCalParamDataV9(String symbol, CompanySharePriceResult result,
                                                           CompanySharePriceResultDetail resultDetail,
                                                           StockQuoteResDto stockQuote,
                                                           List<StockPriceVolumeResDto> priceHistory,
                                                           String sector)
            throws InterruptedException {

        if(log.isDebugEnabled()) log.debug("[V9 계산 로그] [{}] {} 계산 시작", symbol, result.get기업명());

        CompanySharePriceCalculator calParam = new CompanySharePriceCalculator();

        //@ 필요데이터 조회 (연간 영업이익 3년)
        // Thread.sleep(TRSC_DELAY); // FmpRateLimiter로 대체 (2026.03.10)
        IncomeStatReqDto incomeStatReqDto = new IncomeStatReqDto(symbol, 3, FmpPeriod.annual);
        List<IncomeStatResDto> income = incomeStatementService.findIncomeStat(incomeStatReqDto);
        if(income == null || income.size() < 3) {
            result.set결과메시지("영업이익 조회에 실패했습니다.");
            return null;
        } else {
            for(IncomeStatResDto ic : income) {
                if(!ic.getReportedCurrency().equals(CurrencyConst.USA)) {
                    double rate = helper.getForexQuotePriceUSD(ic.getReportedCurrency());
                    if(rate != -1) ic.applyExchangeRate(rate);
                }
            }
        }

        //@ 분기 영업이익 4개 조회
        // Thread.sleep(TRSC_DELAY); // FmpRateLimiter로 대체 (2026.03.10)
        IncomeStatReqDto quarterlyIncomeReqDto = new IncomeStatReqDto(symbol, 4, FmpPeriod.quarter);
        List<IncomeStatResDto> quarterlyIncome = incomeStatementService.findIncomeStat(quarterlyIncomeReqDto);
        if(quarterlyIncome != null && quarterlyIncome.size() >= 4) {
            for(IncomeStatResDto qi : quarterlyIncome) {
                if(!qi.getReportedCurrency().equals(CurrencyConst.USA)) {
                    double rate = helper.getForexQuotePriceUSD(qi.getReportedCurrency());
                    if(rate != -1) qi.applyExchangeRate(rate);
                }
            }
            calParam.setQuarterlyOpIncomeQ1(StringUtil.defaultString(quarterlyIncome.get(0).getOperatingIncome()));
            calParam.setQuarterlyOpIncomeQ2(StringUtil.defaultString(quarterlyIncome.get(1).getOperatingIncome()));
            calParam.setQuarterlyOpIncomeQ3(StringUtil.defaultString(quarterlyIncome.get(2).getOperatingIncome()));
            calParam.setQuarterlyOpIncomeQ4(StringUtil.defaultString(quarterlyIncome.get(3).getOperatingIncome()));
        }

        // Thread.sleep(TRSC_DELAY); // FmpRateLimiter로 대체 (2026.03.10)
        BalanceSheetReqDto balanceSheetReqDto = new BalanceSheetReqDto(symbol, 1, FmpPeriod.quarter);
        List<BalanceSheetResDto> balance = balanceSheetStatementService.findBalanceSheet(balanceSheetReqDto);
        if(balance == null || balance.size() < 1) {
            result.set결과메시지("재무상태표 조회에 실패했습니다.");
            return null;
        } else {
            for(BalanceSheetResDto bs : balance) {
                if(!bs.getReportedCurrency().equals(CurrencyConst.USA)) {
                    double rate = helper.getForexQuotePriceUSD(bs.getReportedCurrency());
                    if(rate != -1) bs.applyExchangeRate(rate);
                }
            }
        }

        // Thread.sleep(TRSC_DELAY); // FmpRateLimiter로 대체 (2026.03.10)
        EnterpriseValuesReqDto enterpriseValuesReqDto = new EnterpriseValuesReqDto(symbol, 1, FmpPeriod.quarter);
        List<EnterpriseValuesResDto> enterpriseValues = enterpriseValueService.findEnterpriseValue(enterpriseValuesReqDto);
        if(enterpriseValues == null || enterpriseValues.size() < 1) {
            result.set결과메시지("기업가치 조회에 실패했습니다.");
            return null;
        }

        // Thread.sleep(TRSC_DELAY); // FmpRateLimiter로 대체 (2026.03.10)
        FinancialRatiosTTM_ReqDto financialRatiosTTM_ReqDto = new FinancialRatiosTTM_ReqDto(symbol);
        List<FinancialRatiosTTM_ResDto> financialRatios = financialRatiosService.findFinancialRatiosTTM(financialRatiosTTM_ReqDto);
        if(financialRatios == null || financialRatios.size() < 1) {
            result.set결과메시지("재무비율지표(TTM) 조회에 실패했습니다.");
            return null;
        }

        // Thread.sleep(TRSC_DELAY); // FmpRateLimiter로 대체 (2026.03.10)
        List<FinancialRatiosResDto> historicalRatios = null;
        try {
            FinancialRatiosReqDto historicalRatiosReqDto = new FinancialRatiosReqDto(symbol, 5, FmpPeriod.annual);
            historicalRatios = financialRatiosService.findFinancialRatios(historicalRatiosReqDto);
        } catch (Exception e) {
            log.warn("[V9] {} - 연간 재무비율 조회 실패 (TTM PER로 폴백): {}", symbol, e.getMessage());
        }
        // 진단 로그: API 응답 원본 확인
        if(log.isDebugEnabled()) {
            if (historicalRatios != null) {
                log.debug("[V9] {} - 연간 재무비율 {}건 조회, PER 원본: {}",
                    symbol, historicalRatios.size(),
                    historicalRatios.stream().map(r -> r.getDate() + "=" + r.getPriceToEarningsRatio()).collect(Collectors.joining(", ")));
            } else {
                log.debug("[V9] {} - 연간 재무비율 조회 결과 null", symbol);
            }
        }

        // Thread.sleep(TRSC_DELAY); // FmpRateLimiter로 대체 (2026.03.10)
        FinancialGrowthReqDto financialGrowthReqDto = new FinancialGrowthReqDto(symbol, 1, FmpPeriod.fiscalYear);
        List<FinancialGrowthResDto> financialGrowth = financialGrowthService.financialStatementsGrowth(financialGrowthReqDto);
        if(financialGrowth == null || financialGrowth.size() < 1) {
            result.set결과메시지("성장률 조회에 실패했습니다.");
            return null;
        }

        // Thread.sleep(TRSC_DELAY); // FmpRateLimiter로 대체 (2026.03.10)
        IncomeStatGrowthReqDto incomeStatGrowthReqDto = new IncomeStatGrowthReqDto(symbol, 1, FmpPeriod.annual);
        List<IncomeStatGrowthResDto> incomeStatGrowth = incomeStatGrowthService.findIncomeStatGrowth(incomeStatGrowthReqDto);
        if(incomeStatGrowth == null || incomeStatGrowth.size() < 1) {
            result.set결과메시지("영업이익 성장률 조회에 실패했습니다.");
            return null;
        }

        // ----------------------------------------------------------------

        //@ 영업이익
        helper.setIncomeStat(calParam, income, resultDetail);

        //@ 매출정보
        helper.setPsr(calParam, income, incomeStatGrowth, financialRatios);

        //@ 유동자산 합계
        String assetsCurrent = StringUtil.defaultString(balance.get(0).getTotalCurrentAssets());
        calParam.setCurrentAssetsTotal(assetsCurrent);
        resultDetail.set유동자산합계(assetsCurrent);

        //@ 유동부채 합계
        String liabilitiesCurrent = StringUtil.defaultString(balance.get(0).getTotalCurrentLiabilities());
        calParam.setCurrentLiabilitiesTotal(liabilitiesCurrent);
        resultDetail.set유동부채합계(liabilitiesCurrent);

        //@ 유동비율
        String ratio;
        if("0".equals(liabilitiesCurrent) && "0".equals(assetsCurrent)) {
            ratio = "1.0";
        } else if("0".equals(liabilitiesCurrent)) {
            ratio = "2.0";
        } else if("0".equals(assetsCurrent)) {
            ratio = "0.0";
        } else {
            ratio = CalUtil.divide(assetsCurrent, liabilitiesCurrent, 2, RoundingMode.HALF_UP);
        }

        calParam.setCurrentRatio(ratio);
        resultDetail.set유동비율(ratio);

        //@ 투자자산
        String longTermInvestments = StringUtil.defaultString(balance.get(0).getLongTermInvestments());
        calParam.setInvestmentAssets(longTermInvestments);
        resultDetail.set투자자산_비유동자산내(longTermInvestments);

        //@ 무형자산 + 영업권 합산
        String intangibleAssets = StringUtil.defaultString(balance.get(0).getGoodwillAndIntangibleAssets());
        calParam.setIntangibleAssets(intangibleAssets);
        resultDetail.set무형자산(intangibleAssets);

        //@ 발행주식수
        String numberOfShares = StringUtil.defaultString(enterpriseValues.get(0).getNumberOfShares());
        calParam.setIssuedShares(numberOfShares);
        resultDetail.set발행주식수(numberOfShares);

        //@ PER (5년 평균 PER - 현재 주가 의존성 제거)
        String per;
        if (historicalRatios != null && !historicalRatios.isEmpty()) {
            List<Double> validPERs = historicalRatios.stream()
                    .map(FinancialRatiosResDto::getPriceToEarningsRatio)
                    .filter(p -> p != null && p > 0)
                    .collect(Collectors.toList());
            if (!validPERs.isEmpty()) {
                double avgPER = validPERs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                per = String.valueOf(avgPER);
                if(log.isDebugEnabled()) log.debug("[V9] {} - 5년 평균 PER: {} (데이터 {}개, 값: {})", symbol, per, validPERs.size(), validPERs);
            } else {
                per = StringUtil.defaultString(financialRatios.get(0).getPriceToEarningsRatioTTM());
                log.warn("[V9] {} - 연간 PER 데이터 없음, TTM PER({})로 폴백", symbol, per);
            }
        } else {
            per = StringUtil.defaultString(financialRatios.get(0).getPriceToEarningsRatioTTM());
            log.warn("[V9] {} - 연간 PER 데이터 없음, TTM PER({})로 폴백", symbol, per);
        }
        calParam.setPer(per);
        resultDetail.setPER(per);

        //@ PBR (그레이엄 스크리닝용)
        Double pbrTTM = financialRatios.get(0).getPriceToBookRatioTTM();
        if (pbrTTM != null) {
            resultDetail.setPBR(String.valueOf(pbrTTM));
        }

        //@ PBR 기반 평가 데이터 (Financial Services)
        // - 섹터는 프로필 조회값을 인자로 받음 (result.섹터는 계산 후에야 세팅되므로 사용 불가)
        // - BPS(ratios-TTM)는 환율 변환이 안 되므로 USD 공시 기업만 PBR 경로 허용, 그 외는 PER 경로 유지
        boolean usdReporter = CurrencyConst.USA.equals(balance.get(0).getReportedCurrency());
        if (SectorParameterFactory.getParameters(sector).isUsePbrValuation() && usdReporter) {
            Double bps = financialRatios.get(0).getBookValuePerShareTTM();
            Double netIncomePS = financialRatios.get(0).getNetIncomePerShareTTM();
            Double equityPS = financialRatios.get(0).getShareholdersEquityPerShareTTM();
            if (bps != null && bps > 0) {
                calParam.setBookValuePerShare(String.valueOf(bps));
                resultDetail.setBPS(String.valueOf(bps));
            }
            if (netIncomePS != null && equityPS != null && equityPS > 0) {
                double roe = netIncomePS / equityPS;
                calParam.setRoe(String.valueOf(roe));
                resultDetail.setROE(String.format("%.4f", roe));
            }
        }

        //@ 성장률(연간)
        String epsGrowth = StringUtil.defaultString(financialGrowth.get(0).getEpsgrowth());
        calParam.setEpsgrowth(epsGrowth);
        resultDetail.setEPS성장률(epsGrowth);

        String incomeGrowth = StringUtil.defaultString(incomeStatGrowth.get(0).getGrowthOperatingIncome());
        calParam.setOperatingIncomeGrowth(incomeGrowth);
        resultDetail.set영업이익성장률(incomeGrowth);

        //@ 최근 3년 R&D
        helper.setRnDStat(calParam, income, resultDetail);

        //@ 순부채
        // FMP stable totalDebt 는 단기+장기차입금에 리스부채가 이미 포함됨 → capitalLease 별도 가산 안 함
        Long totalDebtVal = balance.get(0).getTotalDebt();
        String totalDebt = StringUtil.defaultString(totalDebtVal != null ? totalDebtVal : 0L);

        String cash = StringUtil.defaultString(balance.get(0).getCashAndCashEquivalents());
        if(StringUtil.isStringEmpty(cash) || "0".equals(cash))
            cash = StringUtil.defaultString(balance.get(0).getCashAndShortTermInvestments());

        calParam.setTotalDebt(totalDebt);
        calParam.setCashAndCashEquivalents(cash);

        resultDetail.set총부채(totalDebt);
        resultDetail.set현금성자산(cash);

        //@ V9: 이자비용 / 유효세율 (최근 연간, 환율 변환 완료된 income 사용)
        IncomeStatResDto latestAnnual = income.get(0);
        calParam.setInterestExpense(StringUtil.defaultString(latestAnnual.getInterestExpense()));
        Long pretaxIncome = latestAnnual.getIncomeBeforeTax();
        Long taxExpense = latestAnnual.getIncomeTaxExpense();
        if (pretaxIncome != null && pretaxIncome > 0 && taxExpense != null) {
            calParam.setEffectiveTaxRate(CalUtil.divide(String.valueOf(taxExpense), String.valueOf(pretaxIncome), 4, RoundingMode.HALF_UP));
        }

        //@ V9: 현재가 (PSR 경로 시가총액×3 상한용, V8 은 미세팅으로 상한 미작동)
        if (stockQuote != null && stockQuote.getPrice() != null) {
            calParam.setPrice(stockQuote.getPrice());
        }

        //@ V8: SMA50, SMA200 (StockQuote에서)
        if (stockQuote != null) {
            if (stockQuote.getPriceAvg50() != null) {
                resultDetail.setSMA50(String.valueOf(stockQuote.getPriceAvg50()));
            }
            if (stockQuote.getPriceAvg200() != null) {
                resultDetail.setSMA200(String.valueOf(stockQuote.getPriceAvg200()));
            }
        }

        return calParam;
    }

    /**
     * V9: 자기자본법 적정가 + V8 후처리(그레이엄→동적 안전마진→매매가)
     */
    public CompanySharePriceResult calPerValueV9(String symbol)
            throws Exception {

        final String VERSION = "v9";
        final String UNIT = "1";
        final String resultDataRedisKey = RedisKeyGenerator.genAbroadCompValueRstData(symbol, VERSION);

        //@ Redis 저장값 확인(캐시 역할) - 현재가격만 실시간 갱신
        String saveData = redisComponent.getValue(resultDataRedisKey);
        if(!StringUtil.isStringEmpty(saveData)) {
            CompanySharePriceResult cached = new Gson().fromJson(saveData, CompanySharePriceResult.class);
            String latestPrice = fetchLatestPrice(symbol);
            if(latestPrice != null) {
                cached.set현재가격(latestPrice);
            }
            return cached;
        }

        CompanySharePriceResult result = new CompanySharePriceResult();
        result.set기업심볼(symbol);
        CompanySharePriceResultDetail resultDetail = new CompanySharePriceResultDetail(UNIT);

        //@1. 정보 조회 ---------------------------------
        CompanyProfileDataResDto companyProfile = helper.getCompanyProfile(symbol, result);
        if(log.isDebugEnabled()) log.debug("[V9] 기업 정보 = {}", companyProfile);
        if(companyProfile == null) {
            helper.errorProcess(result, "기업 정보 조회에 실패했습니다.");
            return result;
        }

        String sector = companyProfile.getSector();
        if(log.isDebugEnabled()) log.debug("[V9] 섹터 정보: {}", sector);

        //@1-1. V8 추가: StockQuote 조회 (SMA50, SMA200, price)
        // Thread.sleep(TRSC_DELAY); // FmpRateLimiter로 대체 (2026.03.10)
        StockQuoteResDto stockQuote = null;
        try {
            StockQuoteReqDto quoteReq = new StockQuoteReqDto(symbol);
            List<StockQuoteResDto> quotes = stockQuoteService.findStockQuote(quoteReq);
            if (quotes != null && !quotes.isEmpty()) {
                stockQuote = quotes.get(0);
            }
        } catch (Exception e) {
            log.warn("[V9] {} - StockQuote 조회 실패: {}", symbol, e.getMessage());
        }

        //@1-2. 52주 가격 히스토리 조회 (RSI, 거래량 분석용)
        List<StockPriceVolumeResDto> priceHistory = null;
        try {
            LocalDate today = LocalDate.now();
            LocalDate oneYearAgo = today.minusYears(1);
            StockPriceVolumeReqDto priceReqDto = new StockPriceVolumeReqDto(
                symbol,
                oneYearAgo.format(DateTimeFormatter.ISO_LOCAL_DATE),
                today.format(DateTimeFormatter.ISO_LOCAL_DATE)
            );
            // Thread.sleep(TRSC_DELAY); // FmpRateLimiter로 대체 (2026.03.10)
            priceHistory = stockPriceVolumeService.findStockPriceVolume(priceReqDto);
        } catch (Exception e) {
            log.warn("[V9] {} - 52주 가격 히스토리 조회 실패: {}", symbol, e.getMessage());
        }

        //@2. 재무 데이터 조회
        CompanySharePriceCalculator calParam = getCalParamDataV9(symbol, result, resultDetail, stockQuote, priceHistory, sector);
        if(calParam == null) {
            helper.errorProcess(result, "재무정보 조회에 실패했습니다.");
            return result;
        }
        calParam.setUnit(UNIT);

        //@2-1. PBR 데이터
        Double pbrTTM = null;
        String pbrStr = resultDetail.getPBR();
        if (pbrStr != null && !pbrStr.isEmpty()) {
            try { pbrTTM = Double.parseDouble(pbrStr); } catch (NumberFormatException ignored) {}
        }

        //@2-2. Beta (PBR 기반 평가용)
        if (companyProfile.getBeta() != null) {
            calParam.setBeta(String.valueOf(companyProfile.getBeta()));
        }

        //@2-3. Industry (Financial Services 서브섹터 분화용)
        calParam.setIndustry(companyProfile.getIndustry());

        //@3. 계산 (V9 로직) ---------------------------------
        if(log.isDebugEnabled()) log.debug("[V9] {} - 계산 입력: PER={}, 섹터={}", symbol, calParam.getPer(), sector);
        String 계산된주당가치 = sharePriceCalculatorService.calPerValueV9(calParam, resultDetail, sector);

        //@4. 그레이엄 스크리닝 (안전마진이 통과수를 사용하므로 먼저 수행) ---------------------------------
        applyGrahamScreeningV9(symbol, sector, calParam, resultDetail, pbrTTM);

        //@5. V8: 동적 안전마진 ---------------------------------
        double totalMargin = calcHelper.calculateDynamicSafetyMargin(
                companyProfile.getBeta(), resultDetail.get그레이엄_통과수());
        resultDetail.set안전마진율(String.format("%.0f%%", totalMargin * 100));

        //@6. 매매가 산출 ---------------------------------
        String 매수적정가 = calcHelper.calculatePurchasePrice(계산된주당가치, totalMargin);
        String 목표매도가 = calcHelper.calculateSellTarget(계산된주당가치);
        String 손절매가 = calcHelper.calculateStopLoss(매수적정가);

        //@7. 결과 조립 ---------------------------------
        result.set버전(VERSION.toUpperCase());
        result.set섹터(sector);
        if(StringUtil.isStringEmpty(result.get결과메시지())) result.set결과메시지("정상 처리되었습니다.");
        result.set주당가치(계산된주당가치);
        result.set계산된주당가치(계산된주당가치);
        result.set매수적정가(매수적정가);
        result.set목표매도가(목표매도가);
        result.set손절매가(손절매가);
        result.set안전마진율(String.format("%.0f%%", totalMargin * 100));
        // 현재가: StockQuote + AftermarketTrade 비교하여 최신 가격 사용
        String latestPrice = fetchLatestPrice(symbol);
        if(latestPrice != null) {
            result.set현재가격(latestPrice);
        } else if(stockQuote != null && stockQuote.getPrice() != null) {
            result.set현재가격(String.valueOf(stockQuote.getPrice()));
        } else {
            result.set현재가격(StringUtil.defaultString(companyProfile.getPrice()));
        }
        result.set확인시간(DateUtil.getToday("yyyy-MM-dd HH:mm:ss"));
        helper.setRstDetailContextData(resultDetail);

        result.set상세정보(resultDetail);

        //@ Redis에 결과값 저장(캐시 역할)
        redisComponent.saveValueWithTtl(resultDataRedisKey, new Gson().toJson(result), 6, TimeUnit.HOURS);

        return result;
    }

    /**
     * V9: 그레이엄 스크리닝 (PER/PBR/복합/유동비율/연속흑자 5개 기준 통과수 → resultDetail)
     * - 동적 안전마진이 통과수를 사용하므로 반드시 안전마진 계산 전에 호출
     */
    private void applyGrahamScreeningV9(String symbol, String sector, CompanySharePriceCalculator calParam,
                                        CompanySharePriceResultDetail resultDetail, Double pbrTTM) {
        calcHelper.applyGrahamScreening(symbol, SectorParameterFactory.getParameters(sector), calParam, resultDetail, pbrTTM);
    }

    /**
     * 주당 가치 계산(다건) V9
     */
    public List<CompanySharePriceResult> calPerValueListV9(String symbols, String detail) throws Exception {

        final int MAX_CAL_SYMBOL_SIZE = 30;

        List<CompanySharePriceResult> resultList = new LinkedList<>();

        if(symbols == null) return null;
        List<String> symbolList = Arrays.stream(symbols.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        if(symbolList.size() > MAX_CAL_SYMBOL_SIZE) {
            throw new BizException("동시 조회는 " + MAX_CAL_SYMBOL_SIZE + "건 까지만 가능합니다.");
        }

        for(String symbol : symbolList) {
            fmpRateLimiter.waitIfNeeded();  // Rate limit 종목 간 체크
            try {
                CompanySharePriceResult result = calPerValueV9(symbol);
                if("F".equals(StringUtil.defaultString(detail))) result.set상세정보(null);
                resultList.add(result);
            } catch (Exception e) {
                log.error("[V9 대량조회] {} 처리 중 오류: {}", symbol, e.getMessage());
                CompanySharePriceResult errorResult = new CompanySharePriceResult();
                errorResult.set기업심볼(symbol);
                helper.errorProcess(errorResult, "처리 중 오류가 발생했습니다.");
                resultList.add(errorResult);
            }
            // Thread.sleep(TRSC_DELAY); // FmpRateLimiter로 대체 (2026.03.10)
        }

        return resultList;
    }

    /**
     * 정규장 + 애프터마켓 중 최신 가격 조회
     * @param symbol 종목 심볼
     * @return 최신 가격 문자열, 조회 실패 시 null
     */
    private String fetchLatestPrice(String symbol) {
        try {
            List<StockQuoteResDto> quotes = stockQuoteService.findStockQuote(new StockQuoteReqDto(symbol));
            if(quotes == null || quotes.isEmpty()) return null;

            StockQuoteResDto quote = quotes.get(0);
            Double price = quote.getPrice();
            if(price == null) return null;

            // 애프터마켓 조회
            List<AfterTradeResDto> afterTrades = afterTradeService.findAfterTrade(new AfterTradeReqDto(symbol));
            if(afterTrades != null && !afterTrades.isEmpty()) {
                AfterTradeResDto afterTrade = afterTrades.get(0);

                long quoteTimestamp = quote.getTimestamp();
                long afterTradeTimestamp = afterTrade.getTimestamp();

                // 단위 통일: 밀리초 → 초로 변환
                long afterTradeTimestampSec = afterTradeTimestamp > 9_999_999_999L
                        ? afterTradeTimestamp / 1000
                        : afterTradeTimestamp;

                if(afterTradeTimestampSec > quoteTimestamp && afterTrade.getPrice() != null) {
                    price = afterTrade.getPrice();
                }
            }

            return String.valueOf(price);
        } catch (Exception e) {
            log.warn("[fetchLatestPrice] {} - 최신 가격 조회 실패: {}", symbol, e.getMessage());
            return null;
        }
    }
}
