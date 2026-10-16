package com.finance.dart.api.abroad.service.legacy;

import com.finance.dart.api.abroad.consts.CurrencyConst;
import com.finance.dart.api.abroad.consts.FmpPeriod;
import com.finance.dart.api.abroad.dto.fmp.balancesheet.BalanceSheetReqDto;
import com.finance.dart.api.abroad.dto.fmp.balancesheet.BalanceSheetResDto;
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
import com.finance.dart.api.abroad.service.US_StockCalHelper;
import com.finance.dart.api.abroad.service.fmp.*;
import com.finance.dart.api.common.constants.EvaluationConst;
import com.finance.dart.api.common.context.RequestContext;
import com.finance.dart.api.common.dto.CompanySharePriceCalculator;
import com.finance.dart.api.common.dto.CompanySharePriceResult;
import com.finance.dart.api.common.dto.CompanySharePriceResultDetail;
import com.finance.dart.api.common.dto.SectorCalculationParameters;
import com.finance.dart.api.common.service.PerShareValueCalculationService;
import com.finance.dart.api.common.service.legacy.PerShareValueCalcLegacyService;
import com.finance.dart.api.common.service.SectorParameterFactory;
import com.finance.dart.common.component.RedisComponent;
import com.finance.dart.common.component.RedisKeyGenerator;
import com.finance.dart.common.exception.BizException;
import com.finance.dart.common.util.CalUtil;
import com.finance.dart.common.util.DateUtil;
import com.finance.dart.common.util.StringUtil;
import com.google.gson.Gson;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 해외기업 주식가치 계산 서비스 - Legacy (V1~V7)
 */
@Slf4j
@AllArgsConstructor
@Service
public class US_StockCalLegacyService {

    private final int TRSC_DELAY = 10;    // 0.01s

    private final RequestContext requestContext;
    private final RedisComponent redisComponent;

    private final IncomeStatementService incomeStatementService;
    private final BalanceSheetStatementService balanceSheetStatementService;
    private final EnterpriseValueService enterpriseValueService;
    private final FinancialRatiosService financialRatiosService;
    private final FinancialGrowthService financialGrowthService;
    private final IncomeStatGrowthService incomeStatGrowthService;
    private final StockPriceVolumeService stockPriceVolumeService;

    private final PerShareValueCalculationService sharePriceCalculatorService;
    private final PerShareValueCalcLegacyService sharePriceCalcLegacyService;
    private final US_StockCalHelper helper;

    // ========================= calPerValueList V1~V7 =========================

    public List<CompanySharePriceResult> calPerValueListV1(String symbols, String detail) throws Exception {

        List<CompanySharePriceResult> resultList = new LinkedList<>();

        if(symbols == null) return null;
        List<String> symbolList = Arrays.stream(symbols.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        for(String symbol : symbolList) {
            CompanySharePriceResult result = calPerValueV1(symbol);
            if("F".equals(StringUtil.defaultString(detail))) result.set상세정보(null);
            resultList.add(result);
            Thread.sleep(TRSC_DELAY);
        }

        return resultList;
    }

    public List<CompanySharePriceResult> calPerValueListV2(String symbols, String detail) throws Exception {

        List<CompanySharePriceResult> resultList = new LinkedList<>();

        if(symbols == null) return null;
        List<String> symbolList = Arrays.stream(symbols.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        for(String symbol : symbolList) {
            CompanySharePriceResult result = calPerValueV2(symbol);
            if("F".equals(StringUtil.defaultString(detail))) result.set상세정보(null);
            resultList.add(result);
            Thread.sleep(TRSC_DELAY);
        }

        return resultList;
    }

    public List<CompanySharePriceResult> calPerValueListV3(String symbols, String detail) throws Exception {

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
            CompanySharePriceResult result = calPerValueV3(symbol);
            if("F".equals(StringUtil.defaultString(detail))) result.set상세정보(null);
            resultList.add(result);
            Thread.sleep(TRSC_DELAY);
        }

        return resultList;
    }

    public List<CompanySharePriceResult> calPerValueListV4(String symbols, String detail) throws Exception {

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
            CompanySharePriceResult result = calPerValueV4(symbol);
            if("F".equals(StringUtil.defaultString(detail))) result.set상세정보(null);
            resultList.add(result);
            Thread.sleep(TRSC_DELAY);
        }

        return resultList;
    }

    public List<CompanySharePriceResult> calPerValueListV5(String symbols, String detail) throws Exception {

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
            try {
                CompanySharePriceResult result = calPerValueV5(symbol);
                if("F".equals(StringUtil.defaultString(detail))) result.set상세정보(null);
                resultList.add(result);
            } catch (Exception e) {
                log.error("[대량조회] {} 처리 중 오류: {}", symbol, e.getMessage());
                CompanySharePriceResult errorResult = new CompanySharePriceResult();
                errorResult.set기업심볼(symbol);
                helper.errorProcess(errorResult, "처리 중 오류가 발생했습니다.");
                resultList.add(errorResult);
            }
            Thread.sleep(TRSC_DELAY);
        }

        return resultList;
    }

    public List<CompanySharePriceResult> calPerValueListV6(String symbols, String detail) throws Exception {

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
            try {
                CompanySharePriceResult result = calPerValueV6(symbol);
                if("F".equals(StringUtil.defaultString(detail))) result.set상세정보(null);
                resultList.add(result);
            } catch (Exception e) {
                log.error("[V6 대량조회] {} 처리 중 오류: {}", symbol, e.getMessage());
                CompanySharePriceResult errorResult = new CompanySharePriceResult();
                errorResult.set기업심볼(symbol);
                helper.errorProcess(errorResult, "처리 중 오류가 발생했습니다.");
                resultList.add(errorResult);
            }
            Thread.sleep(TRSC_DELAY);
        }

        return resultList;
    }

    public List<CompanySharePriceResult> calPerValueListV7(String symbols, String detail) throws Exception {

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
            try {
                CompanySharePriceResult result = calPerValueV7(symbol);
                if("F".equals(StringUtil.defaultString(detail))) result.set상세정보(null);
                resultList.add(result);
            } catch (Exception e) {
                log.error("[V7 대량조회] {} 처리 중 오류: {}", symbol, e.getMessage());
                CompanySharePriceResult errorResult = new CompanySharePriceResult();
                errorResult.set기업심볼(symbol);
                helper.errorProcess(errorResult, "처리 중 오류가 발생했습니다.");
                resultList.add(errorResult);
            }
            Thread.sleep(TRSC_DELAY);
        }

        return resultList;
    }

    // ========================= calPerValue V1~V7 =========================

    public CompanySharePriceResult calPerValueV1(String symbol) throws Exception {

        final String UNIT = "1";

        CompanySharePriceResult result = new CompanySharePriceResult();
        result.set기업심볼(symbol);
        CompanySharePriceResultDetail resultDetail = new CompanySharePriceResultDetail(UNIT);

        CompanyProfileDataResDto companyProfile = helper.getCompanyProfile(symbol, result);
        if(log.isDebugEnabled()) log.debug("기업 정보 = {}", companyProfile);
        if(companyProfile == null) {
            helper.errorProcess(result, "기업 정보 조회에 실패했습니다.");
            return result;
        }

        CompanySharePriceCalculator calParam = getCalParamData(symbol, result, resultDetail);
        if(calParam == null) {
            helper.errorProcess(result, "재무정보 조회에 실패했습니다.");
            return result;
        }
        calParam.setUnit(UNIT);
        if(log.isDebugEnabled()) log.debug("계산 정보 = {}", calParam);

        result.set주당가치(sharePriceCalculatorService.calPerValue(calParam));

        if(StringUtil.isStringEmpty(result.get결과메시지())) result.set결과메시지("정상 처리되었습니다.");
        result.set현재가격(StringUtil.defaultString(companyProfile.getPrice()));
        result.set확인시간(DateUtil.getToday("yyyy-MM-dd HH:mm:ss"));
        helper.setRstDetailContextData(resultDetail);

        result.set상세정보(resultDetail);

        return result;
    }

    public CompanySharePriceResult calPerValueV2(String symbol) throws Exception {

        final String UNIT = "1";
        final String resultDataRedisKey = RedisKeyGenerator.genAbroadCompValueRstData(symbol, "v2");

        String saveData = redisComponent.getValue(resultDataRedisKey);
        if(!StringUtil.isStringEmpty(saveData)) {
            return new Gson().fromJson(saveData, CompanySharePriceResult.class);
        }

        CompanySharePriceResult result = new CompanySharePriceResult();
        result.set기업심볼(symbol);
        CompanySharePriceResultDetail resultDetail = new CompanySharePriceResultDetail(UNIT);

        CompanyProfileDataResDto companyProfile = helper.getCompanyProfile(symbol, result);
        if(log.isDebugEnabled()) log.debug("기업 정보 = {}", companyProfile);
        if(companyProfile == null) {
            helper.errorProcess(result, "기업 정보 조회에 실패했습니다.");
            return result;
        }

        CompanySharePriceCalculator calParam = getCalParamDataV2(symbol, result, resultDetail);
        if(calParam == null) {
            helper.errorProcess(result, "재무정보 조회에 실패했습니다.");
            return result;
        }
        calParam.setUnit(UNIT);
        if(log.isDebugEnabled()) log.debug("계산 정보 = {}", calParam);

        result.set주당가치(sharePriceCalcLegacyService.calPerValueV2(calParam, resultDetail));

        if(StringUtil.isStringEmpty(result.get결과메시지())) result.set결과메시지("정상 처리되었습니다.");
        result.set현재가격(StringUtil.defaultString(companyProfile.getPrice()));
        result.set확인시간(DateUtil.getToday("yyyy-MM-dd HH:mm:ss"));
        helper.setRstDetailContextData(resultDetail);

        result.set상세정보(resultDetail);

        redisComponent.saveValueWithTtl(resultDataRedisKey, new Gson().toJson(result), 6, TimeUnit.HOURS);

        return result;
    }

    public CompanySharePriceResult calPerValueV3(String symbol) throws Exception {

        final String UNIT = "1";
        final String resultDataRedisKey = RedisKeyGenerator.genAbroadCompValueRstData(symbol, "v3");

        String saveData = redisComponent.getValue(resultDataRedisKey);
        if(!StringUtil.isStringEmpty(saveData)) {
            return new Gson().fromJson(saveData, CompanySharePriceResult.class);
        }

        CompanySharePriceResult result = new CompanySharePriceResult();
        result.set기업심볼(symbol);
        CompanySharePriceResultDetail resultDetail = new CompanySharePriceResultDetail(UNIT);

        CompanyProfileDataResDto companyProfile = helper.getCompanyProfile(symbol, result);
        if(log.isDebugEnabled()) log.debug("기업 정보 = {}", companyProfile);
        if(companyProfile == null) {
            helper.errorProcess(result, "기업 정보 조회에 실패했습니다.");
            return result;
        }

        CompanySharePriceCalculator calParam = getCalParamDataV5(symbol, result, resultDetail);
        if(calParam == null) {
            helper.errorProcess(result, "재무정보 조회에 실패했습니다.");
            return result;
        }
        calParam.setUnit(UNIT);
        if(log.isDebugEnabled()) log.debug("계산 정보 = {}", calParam);

        String 계산된주당가치 = sharePriceCalcLegacyService.calPerValueV3(calParam, resultDetail);

        String 조정된주당가치;
        Double historicalHigh3Y = helper.get3YearsHistoricalHighPrice(symbol);

        if (historicalHigh3Y != null) {
            BigDecimal 계산값 = new BigDecimal(계산된주당가치);
            BigDecimal 최고가 = BigDecimal.valueOf(historicalHigh3Y);

            if (계산값.compareTo(최고가) > 0) {
                조정된주당가치 = 최고가.multiply(new BigDecimal("0.8"))
                        .setScale(2, RoundingMode.HALF_UP)
                        .toPlainString();
                if(log.isDebugEnabled()) {
                    log.debug("[적정가 조정] {} - 계산값({}) > 3년내최고가({}) → 조정값({})",
                            symbol, 계산값, 최고가, 조정된주당가치);
                }
            } else {
                조정된주당가치 = 계산된주당가치;
            }
        } else {
            조정된주당가치 = 계산된주당가치;
            if(log.isDebugEnabled()) {
                log.debug("[적정가 조정] {} - 3년내 최고가 데이터 없음, 원본값 사용", symbol);
            }
        }

        result.set버전("V3");
        if(StringUtil.isStringEmpty(result.get결과메시지())) result.set결과메시지("정상 처리되었습니다.");
        result.set주당가치(조정된주당가치);
        result.set계산된주당가치(계산된주당가치);
        result.set현재가격(StringUtil.defaultString(companyProfile.getPrice()));
        result.set확인시간(DateUtil.getToday("yyyy-MM-dd HH:mm:ss"));
        helper.setRstDetailContextData(resultDetail);

        result.set상세정보(resultDetail);

        redisComponent.saveValueWithTtl(resultDataRedisKey, new Gson().toJson(result), 6, TimeUnit.HOURS);

        return result;
    }

    public CompanySharePriceResult calPerValueV4(String symbol) throws Exception {

        final String UNIT = "1";
        final String resultDataRedisKey = RedisKeyGenerator.genAbroadCompValueRstData(symbol, "v4");

        String saveData = redisComponent.getValue(resultDataRedisKey);
        if(!StringUtil.isStringEmpty(saveData)) {
            return new Gson().fromJson(saveData, CompanySharePriceResult.class);
        }

        CompanySharePriceResult result = new CompanySharePriceResult();
        result.set기업심볼(symbol);
        CompanySharePriceResultDetail resultDetail = new CompanySharePriceResultDetail(UNIT);

        CompanyProfileDataResDto companyProfile = helper.getCompanyProfile(symbol, result);
        if(log.isDebugEnabled()) log.debug("기업 정보 = {}", companyProfile);
        if(companyProfile == null) {
            helper.errorProcess(result, "기업 정보 조회에 실패했습니다.");
            return result;
        }

        String sector = companyProfile.getSector();
        if(log.isDebugEnabled()) log.debug("[V4] 섹터 정보: {}", sector);

        CompanySharePriceCalculator calParam = getCalParamDataV5(symbol, result, resultDetail);
        if(calParam == null) {
            helper.errorProcess(result, "재무정보 조회에 실패했습니다.");
            return result;
        }
        calParam.setUnit(UNIT);
        if(log.isDebugEnabled()) log.debug("계산 정보 = {}", calParam);

        String 계산된주당가치 = sharePriceCalcLegacyService.calPerValueV4(calParam, resultDetail, sector);

        String 조정된주당가치;
        Double historicalHigh3Y = helper.get3YearsHistoricalHighPrice(symbol);

        if (historicalHigh3Y != null) {
            BigDecimal 계산값 = new BigDecimal(계산된주당가치);
            BigDecimal 최고가 = BigDecimal.valueOf(historicalHigh3Y);

            if (계산값.compareTo(최고가) > 0) {
                조정된주당가치 = 최고가.multiply(new BigDecimal("0.8"))
                                  .setScale(2, RoundingMode.HALF_UP)
                                  .toPlainString();
                if(log.isDebugEnabled()) {
                    log.debug("[적정가 조정] {} - 계산값({}) > 3년내최고가({}) → 조정값({})",
                             symbol, 계산값, 최고가, 조정된주당가치);
                }
            } else {
                조정된주당가치 = 계산된주당가치;
            }
        } else {
            조정된주당가치 = 계산된주당가치;
            if(log.isDebugEnabled()) {
                log.debug("[적정가 조정] {} - 3년내 최고가 데이터 없음, 원본값 사용", symbol);
            }
        }

        result.set버전("V4");
        result.set섹터(sector);
        if(StringUtil.isStringEmpty(result.get결과메시지())) result.set결과메시지("정상 처리되었습니다.");
        result.set주당가치(조정된주당가치);
        result.set계산된주당가치(계산된주당가치);
        result.set현재가격(StringUtil.defaultString(companyProfile.getPrice()));
        result.set확인시간(DateUtil.getToday("yyyy-MM-dd HH:mm:ss"));
        helper.setRstDetailContextData(resultDetail);

        result.set상세정보(resultDetail);

        redisComponent.saveValueWithTtl(resultDataRedisKey, new Gson().toJson(result), 6, TimeUnit.HOURS);

        return result;
    }

    public CompanySharePriceResult calPerValueV5(String symbol) throws Exception {

        final String VERSION = "v5";
        final String UNIT = "1";
        final String resultDataRedisKey = RedisKeyGenerator.genAbroadCompValueRstData(symbol, VERSION);

        String saveData = redisComponent.getValue(resultDataRedisKey);
        if(!StringUtil.isStringEmpty(saveData)) {
            return new Gson().fromJson(saveData, CompanySharePriceResult.class);
        }

        CompanySharePriceResult result = new CompanySharePriceResult();
        result.set기업심볼(symbol);
        CompanySharePriceResultDetail resultDetail = new CompanySharePriceResultDetail(UNIT);

        CompanyProfileDataResDto companyProfile = helper.getCompanyProfile(symbol, result);
        if(log.isDebugEnabled()) log.debug("기업 정보 = {}", companyProfile);
        if(companyProfile == null) {
            helper.errorProcess(result, "기업 정보 조회에 실패했습니다.");
            return result;
        }

        String sector = companyProfile.getSector();
        if(log.isDebugEnabled()) log.debug("섹터 정보: {}", sector);

        CompanySharePriceCalculator calParam = getCalParamDataV5(symbol, result, resultDetail);
        if(calParam == null) {
            helper.errorProcess(result, "재무정보 조회에 실패했습니다.");
            return result;
        }
        calParam.setUnit(UNIT);
        if(log.isDebugEnabled()) log.debug("계산 정보 = {}", calParam);

        String 계산된주당가치 = sharePriceCalcLegacyService.calPerValueV5(calParam, resultDetail, sector);

        String 조정된주당가치;
        Double historicalHigh3Y = helper.get3YearsHistoricalHighPrice(symbol);

        if (historicalHigh3Y != null) {
            BigDecimal 계산값 = new BigDecimal(계산된주당가치);
            BigDecimal 최고가 = BigDecimal.valueOf(historicalHigh3Y);

            if (계산값.compareTo(최고가) > 0) {
                조정된주당가치 = 최고가.multiply(new BigDecimal("0.8"))
                        .setScale(2, RoundingMode.HALF_UP)
                        .toPlainString();
                if(log.isDebugEnabled()) {
                    log.debug("[적정가 조정] {} - 계산값({}) > 3년내최고가({}) → 조정값({})",
                            symbol, 계산값, 최고가, 조정된주당가치);
                }
            } else {
                조정된주당가치 = 계산된주당가치;
            }
        } else {
            조정된주당가치 = 계산된주당가치;
            if(log.isDebugEnabled()) {
                log.debug("[적정가 조정] {} - 3년내 최고가 데이터 없음, 원본값 사용", symbol);
            }
        }

        result.set버전(VERSION.toUpperCase());
        result.set섹터(sector);
        if(StringUtil.isStringEmpty(result.get결과메시지())) result.set결과메시지("정상 처리되었습니다.");
        result.set주당가치(조정된주당가치);
        result.set계산된주당가치(계산된주당가치);
        result.set현재가격(StringUtil.defaultString(companyProfile.getPrice()));
        result.set확인시간(DateUtil.getToday("yyyy-MM-dd HH:mm:ss"));
        helper.setRstDetailContextData(resultDetail);

        result.set상세정보(resultDetail);

        redisComponent.saveValueWithTtl(resultDataRedisKey, new Gson().toJson(result), 6, TimeUnit.HOURS);

        return result;
    }

    public CompanySharePriceResult calPerValueV6(String symbol) throws Exception {

        final String VERSION = "v6";
        final String UNIT = "1";
        final String resultDataRedisKey = RedisKeyGenerator.genAbroadCompValueRstData(symbol, VERSION);

        String saveData = redisComponent.getValue(resultDataRedisKey);
        if(!StringUtil.isStringEmpty(saveData)) {
            return new Gson().fromJson(saveData, CompanySharePriceResult.class);
        }

        CompanySharePriceResult result = new CompanySharePriceResult();
        result.set기업심볼(symbol);
        CompanySharePriceResultDetail resultDetail = new CompanySharePriceResultDetail(UNIT);

        CompanyProfileDataResDto companyProfile = helper.getCompanyProfile(symbol, result);
        if(log.isDebugEnabled()) log.debug("기업 정보 = {}", companyProfile);
        if(companyProfile == null) {
            helper.errorProcess(result, "기업 정보 조회에 실패했습니다.");
            return result;
        }

        String sector = companyProfile.getSector();
        if(log.isDebugEnabled()) log.debug("[V6] 섹터 정보: {}", sector);

        CompanySharePriceCalculator calParam = getCalParamDataV6(symbol, result, resultDetail);
        if(calParam == null) {
            helper.errorProcess(result, "재무정보 조회에 실패했습니다.");
            return result;
        }
        calParam.setUnit(UNIT);
        if(log.isDebugEnabled()) log.debug("[V6] 계산 정보 = {}", calParam);

        String 계산된주당가치 = sharePriceCalcLegacyService.calPerValueV6(calParam, resultDetail, sector);

        String 조정된주당가치;
        Double historicalHigh52W = helper.get52WeekHighPrice(symbol);

        if (historicalHigh52W != null) {
            BigDecimal 계산값 = new BigDecimal(계산된주당가치);
            BigDecimal 최고가 = BigDecimal.valueOf(historicalHigh52W);

            if (계산값.compareTo(최고가) > 0) {
                조정된주당가치 = 최고가.multiply(new BigDecimal("0.7"))
                        .setScale(2, RoundingMode.HALF_UP)
                        .toPlainString();
                if(log.isDebugEnabled()) {
                    log.debug("[V6 적정가 조정] {} - 계산값({}) > 52주최고가({}) → 조정값({})",
                            symbol, 계산값, 최고가, 조정된주당가치);
                }
            } else {
                조정된주당가치 = 계산된주당가치;
            }
        } else {
            조정된주당가치 = 계산된주당가치;
            if(log.isDebugEnabled()) {
                log.debug("[V6 적정가 조정] {} - 52주 최고가 데이터 없음, 원본값 사용", symbol);
            }
        }

        result.set버전(VERSION.toUpperCase());
        result.set섹터(sector);
        if(StringUtil.isStringEmpty(result.get결과메시지())) result.set결과메시지("정상 처리되었습니다.");
        result.set주당가치(조정된주당가치);
        result.set계산된주당가치(계산된주당가치);
        result.set현재가격(StringUtil.defaultString(companyProfile.getPrice()));
        result.set확인시간(DateUtil.getToday("yyyy-MM-dd HH:mm:ss"));
        helper.setRstDetailContextData(resultDetail);

        result.set상세정보(resultDetail);

        redisComponent.saveValueWithTtl(resultDataRedisKey, new Gson().toJson(result), 6, TimeUnit.HOURS);

        return result;
    }

    public CompanySharePriceResult calPerValueV7(String symbol) throws Exception {

        final String VERSION = "v7";
        final String UNIT = "1";
        final String resultDataRedisKey = RedisKeyGenerator.genAbroadCompValueRstData(symbol, VERSION);

        String saveData = redisComponent.getValue(resultDataRedisKey);
        if(!StringUtil.isStringEmpty(saveData)) {
            return new Gson().fromJson(saveData, CompanySharePriceResult.class);
        }

        CompanySharePriceResult result = new CompanySharePriceResult();
        result.set기업심볼(symbol);
        CompanySharePriceResultDetail resultDetail = new CompanySharePriceResultDetail(UNIT);

        CompanyProfileDataResDto companyProfile = helper.getCompanyProfile(symbol, result);
        if(log.isDebugEnabled()) log.debug("[V7] 기업 정보 = {}", companyProfile);
        if(companyProfile == null) {
            helper.errorProcess(result, "기업 정보 조회에 실패했습니다.");
            return result;
        }

        String sector = companyProfile.getSector();
        if(log.isDebugEnabled()) log.debug("[V7] 섹터 정보: {}", sector);

        CompanySharePriceCalculator calParam = getCalParamDataV7(symbol, result, resultDetail);
        if(calParam == null) {
            helper.errorProcess(result, "재무정보 조회에 실패했습니다.");
            return result;
        }
        calParam.setUnit(UNIT);
        if(log.isDebugEnabled()) log.debug("[V7] 계산 정보 = {}", calParam);

        Double pbrTTM = null;
        String pbrStr = resultDetail.getPBR();
        if (pbrStr != null && !pbrStr.isEmpty()) {
            try { pbrTTM = Double.parseDouble(pbrStr); } catch (NumberFormatException ignored) {}
        }

        String 계산된주당가치 = sharePriceCalcLegacyService.calPerValueV7(calParam, resultDetail, sector);

        String 조정된주당가치;
        Double historicalHigh52W = helper.get52WeekHighPrice(symbol);

        if (historicalHigh52W != null) {
            BigDecimal 계산값 = new BigDecimal(계산된주당가치);
            BigDecimal 최고가 = BigDecimal.valueOf(historicalHigh52W);

            if (계산값.compareTo(최고가) > 0) {
                조정된주당가치 = 최고가.multiply(new BigDecimal("0.7"))
                        .setScale(2, RoundingMode.HALF_UP)
                        .toPlainString();
                if(log.isDebugEnabled()) {
                    log.debug("[V7 적정가 조정] {} - 계산값({}) > 52주최고가({}) → 조정값({})",
                            symbol, 계산값, 최고가, 조정된주당가치);
                }
            } else {
                조정된주당가치 = 계산된주당가치;
            }

            // 개선D: 52주 고점 대비 30%+ 급락 → 추가 20% 할인
            BigDecimal currentPriceVal = new BigDecimal(StringUtil.defaultString(companyProfile.getPrice(), "0"));
            if (currentPriceVal.compareTo(BigDecimal.ZERO) > 0 && 최고가.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal dropRate = BigDecimal.ONE.subtract(currentPriceVal.divide(최고가, 4, RoundingMode.HALF_UP));
                if (dropRate.compareTo(new BigDecimal("0.3")) >= 0) {
                    조정된주당가치 = new BigDecimal(조정된주당가치)
                            .multiply(new BigDecimal("0.8"))
                            .setScale(2, RoundingMode.HALF_UP)
                            .toPlainString();
                    resultDetail.set급락종목할인(true);
                    if(log.isDebugEnabled()) {
                        log.debug("[V7 급락할인] {} - 현재가({}) 52주최고가({}) 대비 {:.1f}% 하락 → 추가 20% 할인",
                                symbol, currentPriceVal, 최고가, dropRate.multiply(new BigDecimal("100")));
                    }
                }
            }
        } else {
            조정된주당가치 = 계산된주당가치;
            if(log.isDebugEnabled()) {
                log.debug("[V7 적정가 조정] {} - 52주 최고가 데이터 없음, 원본값 사용", symbol);
            }
        }

        String 매수적정가 = new BigDecimal(조정된주당가치)
                .multiply(new BigDecimal("0.7"))
                .setScale(2, RoundingMode.HALF_UP)
                .toPlainString();

        String 목표매도가 = new BigDecimal(조정된주당가치)
                .setScale(2, RoundingMode.HALF_UP)
                .toPlainString();

        // 그레이엄 스크리닝
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
            log.warn("[V7 그레이엄 스크리닝] {} - 스크리닝 실패: {}", symbol, e.getMessage());
        }

        result.set버전(VERSION.toUpperCase());
        result.set섹터(sector);
        if(StringUtil.isStringEmpty(result.get결과메시지())) result.set결과메시지("정상 처리되었습니다.");
        result.set주당가치(조정된주당가치);
        result.set계산된주당가치(계산된주당가치);
        result.set매수적정가(매수적정가);
        result.set목표매도가(목표매도가);
        result.set현재가격(StringUtil.defaultString(companyProfile.getPrice()));
        result.set확인시간(DateUtil.getToday("yyyy-MM-dd HH:mm:ss"));
        helper.setRstDetailContextData(resultDetail);

        result.set상세정보(resultDetail);

        redisComponent.saveValueWithTtl(resultDataRedisKey, new Gson().toJson(result), 6, TimeUnit.HOURS);

        return result;
    }

    // ========================= getCalParamData (V1~V7 전용) =========================

    private CompanySharePriceCalculator getCalParamData(String symbol, CompanySharePriceResult result, CompanySharePriceResultDetail resultDetail)
            throws InterruptedException {

        CompanySharePriceCalculator calParam = new CompanySharePriceCalculator();

        Thread.sleep(TRSC_DELAY);
        IncomeStatReqDto incomeStatReqDto = new IncomeStatReqDto(symbol, 3, FmpPeriod.quarter);
        List<IncomeStatResDto> income = incomeStatementService.findIncomeStat(incomeStatReqDto);
        if(income == null || income.size() < 3) {
            result.set결과메시지("영업이익 조회에 실패했습니다.");
            return null;
        }

        Thread.sleep(TRSC_DELAY);
        BalanceSheetReqDto balanceSheetReqDto = new BalanceSheetReqDto(symbol, 1, FmpPeriod.quarter);
        List<BalanceSheetResDto> balance = balanceSheetStatementService.findBalanceSheet(balanceSheetReqDto);
        if(balance == null || balance.size() < 1) {
            result.set결과메시지("재무상태표 조회에 실패했습니다.");
            return null;
        }

        Thread.sleep(TRSC_DELAY);
        EnterpriseValuesReqDto enterpriseValuesReqDto = new EnterpriseValuesReqDto(symbol, 1, FmpPeriod.quarter);
        List<EnterpriseValuesResDto> enterpriseValues = enterpriseValueService.findEnterpriseValue(enterpriseValuesReqDto);
        if(enterpriseValues == null || enterpriseValues.size() < 1) {
            result.set결과메시지("기업가치 조회에 실패했습니다.");
            return null;
        }

        Thread.sleep(TRSC_DELAY);
        FinancialRatiosReqDto financialRatiosReqDto = new FinancialRatiosReqDto(symbol, 1, FmpPeriod.quarter);
        List<FinancialRatiosResDto> financialRatios = financialRatiosService.findFinancialRatios(financialRatiosReqDto);
        if(financialRatios == null || financialRatios.size() < 1) {
            result.set결과메시지("재무비율지표 조회에 실패했습니다.");
            return null;
        }

        helper.setIncomeStat(calParam, income, resultDetail);

        String assetsCurrent = StringUtil.defaultString(balance.get(0).getTotalCurrentAssets());
        calParam.setCurrentAssetsTotal(assetsCurrent);
        resultDetail.set유동자산합계(assetsCurrent);

        String liabilitiesCurrent = StringUtil.defaultString(balance.get(0).getTotalCurrentLiabilities());
        calParam.setCurrentLiabilitiesTotal(liabilitiesCurrent);
        resultDetail.set유동부채합계(liabilitiesCurrent);

        String ratio = CalUtil.divide(assetsCurrent, liabilitiesCurrent, 2, RoundingMode.HALF_UP);
        calParam.setCurrentRatio(ratio);
        resultDetail.set유동비율(ratio);

        String longTermInvestments = StringUtil.defaultString(balance.get(0).getLongTermInvestments());
        calParam.setInvestmentAssets(longTermInvestments);
        resultDetail.set투자자산_비유동자산내(longTermInvestments);

        String longTermDebt = StringUtil.defaultString(balance.get(0).getLongTermDebt());
        calParam.setFixedLiabilities(longTermDebt);
        resultDetail.set고정부채(longTermDebt);

        String numberOfShares = StringUtil.defaultString(enterpriseValues.get(0).getNumberOfShares());
        calParam.setIssuedShares(numberOfShares);
        resultDetail.set발행주식수(numberOfShares);

        String per = StringUtil.defaultString(financialRatios.get(0).getPriceToEarningsRatio());
        calParam.setPer(per);
        resultDetail.setPER(per);

        return calParam;
    }

    private CompanySharePriceCalculator getCalParamDataV2(String symbol, CompanySharePriceResult result, CompanySharePriceResultDetail resultDetail)
            throws InterruptedException {

        CompanySharePriceCalculator calParam = new CompanySharePriceCalculator();

        Thread.sleep(TRSC_DELAY);
        IncomeStatReqDto incomeStatReqDto = new IncomeStatReqDto(symbol, 3, FmpPeriod.annual);
        List<IncomeStatResDto> income = incomeStatementService.findIncomeStat(incomeStatReqDto);
        if(income == null || income.size() < 3) {
            result.set결과메시지("영업이익 조회에 실패했습니다.");
            return null;
        } else {
            for(IncomeStatResDto ic : income) {
                if(!ic.getReportedCurrency().equals(CurrencyConst.USA)) {
                    double rate = helper.getForexQuotePriceUSD(ic.getReportedCurrency());
                    if(log.isDebugEnabled()) log.debug("[영업이익 환율 반영] {} -> USD / rate = {} / 변경전 = {}", ic.getReportedCurrency(), rate, ic);
                    if(rate != -1) ic.applyExchangeRate(rate);
                    if(log.isDebugEnabled()) log.debug("[영업이익 환율 반영] {} -> USD / rate = {} / 변경후 = {}", ic.getReportedCurrency(), rate, ic);
                }
            }
        }

        Thread.sleep(TRSC_DELAY);
        BalanceSheetReqDto balanceSheetReqDto = new BalanceSheetReqDto(symbol, 1, FmpPeriod.quarter);
        List<BalanceSheetResDto> balance = balanceSheetStatementService.findBalanceSheet(balanceSheetReqDto);
        if(balance == null || balance.size() < 1) {
            result.set결과메시지("재무상태표 조회에 실패했습니다.");
            return null;
        } else {
            for(BalanceSheetResDto bs : balance) {
                if(!bs.getReportedCurrency().equals(CurrencyConst.USA)) {
                    double rate = helper.getForexQuotePriceUSD(bs.getReportedCurrency());
                    if(log.isDebugEnabled()) log.debug("[재무상태표 환율 반영] {} -> USD / rate = {} / 변경전 = {}", bs.getReportedCurrency(), rate, bs);
                    if(rate != -1) bs.applyExchangeRate(rate);
                    if(log.isDebugEnabled()) log.debug("[재무상태표 환율 반영] {} -> USD / rate = {} / 변경후 = {}", bs.getReportedCurrency(), rate, bs);
                }
            }
        }

        Thread.sleep(TRSC_DELAY);
        EnterpriseValuesReqDto enterpriseValuesReqDto = new EnterpriseValuesReqDto(symbol, 1, FmpPeriod.quarter);
        List<EnterpriseValuesResDto> enterpriseValues = enterpriseValueService.findEnterpriseValue(enterpriseValuesReqDto);
        if(enterpriseValues == null || enterpriseValues.size() < 1) {
            result.set결과메시지("기업가치 조회에 실패했습니다.");
            return null;
        }

        Thread.sleep(TRSC_DELAY);
        FinancialRatiosTTM_ReqDto financialRatiosTTM_ReqDto = new FinancialRatiosTTM_ReqDto(symbol);
        List<FinancialRatiosTTM_ResDto> financialRatios = financialRatiosService.findFinancialRatiosTTM(financialRatiosTTM_ReqDto);
        if(financialRatios == null || financialRatios.size() < 1) {
            result.set결과메시지("재무비율지표(TTM) 조회에 실패했습니다.");
            return null;
        }

        FinancialGrowthReqDto financialGrowthReqDto = new FinancialGrowthReqDto(symbol, 1, FmpPeriod.fiscalYear);
        List<FinancialGrowthResDto> financialGrowth = financialGrowthService.financialStatementsGrowth(financialGrowthReqDto);
        if(financialGrowth == null || financialGrowth.size() < 1) {
            result.set결과메시지("성장률 조회에 실패했습니다.");
            return null;
        }

        IncomeStatGrowthReqDto incomeStatGrowthReqDto = new IncomeStatGrowthReqDto(symbol, 1, FmpPeriod.annual);
        List<IncomeStatGrowthResDto> incomeStatGrowth = incomeStatGrowthService.findIncomeStatGrowth(incomeStatGrowthReqDto);
        if(incomeStatGrowth == null || incomeStatGrowth.size() < 1) {
            result.set결과메시지("영업이익 성장률 조회에 실패했습니다.");
            return null;
        }

        helper.setIncomeStat(calParam, income, resultDetail);

        String assetsCurrent = StringUtil.defaultString(balance.get(0).getTotalCurrentAssets());
        calParam.setCurrentAssetsTotal(assetsCurrent);
        resultDetail.set유동자산합계(assetsCurrent);

        String liabilitiesCurrent = StringUtil.defaultString(balance.get(0).getTotalCurrentLiabilities());
        calParam.setCurrentLiabilitiesTotal(liabilitiesCurrent);
        resultDetail.set유동부채합계(liabilitiesCurrent);

        String ratio = CalUtil.divide(assetsCurrent, liabilitiesCurrent, 2, RoundingMode.HALF_UP);
        calParam.setCurrentRatio(ratio);
        resultDetail.set유동비율(ratio);

        String longTermInvestments = StringUtil.defaultString(balance.get(0).getLongTermInvestments());
        calParam.setInvestmentAssets(longTermInvestments);
        resultDetail.set투자자산_비유동자산내(longTermInvestments);

        String intangibleAssets = StringUtil.defaultString(balance.get(0).getIntangibleAssets());
        calParam.setIntangibleAssets(intangibleAssets);
        resultDetail.set무형자산(intangibleAssets);

        String numberOfShares = StringUtil.defaultString(enterpriseValues.get(0).getNumberOfShares());
        calParam.setIssuedShares(numberOfShares);
        resultDetail.set발행주식수(numberOfShares);

        String per = StringUtil.defaultString(financialRatios.get(0).getPriceToEarningsRatioTTM());
        calParam.setPer(per);
        resultDetail.setPER(per);

        String epsGrowth = StringUtil.defaultString(financialGrowth.get(0).getEpsgrowth());
        calParam.setEpsgrowth(epsGrowth);
        resultDetail.setEPS성장률(epsGrowth);

        String incomeGrowth = StringUtil.defaultString(incomeStatGrowth.get(0).getGrowthOperatingIncome());
        calParam.setOperatingIncomeGrowth(incomeGrowth);
        resultDetail.set영업이익성장률(incomeGrowth);

        helper.setRnDStat(calParam, income, resultDetail);

        String totalDebt = StringUtil.defaultString(balance.get(0).getTotalDebt());
        String cash = StringUtil.defaultString(balance.get(0).getCashAndCashEquivalents());
        if(StringUtil.isStringEmpty(cash) || "0".equals(cash))
            cash = StringUtil.defaultString(balance.get(0).getCashAndShortTermInvestments());

        calParam.setTotalDebt(totalDebt);
        calParam.setCashAndCashEquivalents(cash);

        resultDetail.set총부채(totalDebt);
        resultDetail.set현금성자산(cash);

        return calParam;
    }

    private CompanySharePriceCalculator getCalParamDataV5(String symbol, CompanySharePriceResult result, CompanySharePriceResultDetail resultDetail)
            throws InterruptedException {

        if(log.isDebugEnabled()) log.debug("[계산 로그] [{}] {} 계산 시작", symbol, result.get기업명());

        CompanySharePriceCalculator calParam = new CompanySharePriceCalculator();

        Thread.sleep(TRSC_DELAY);
        IncomeStatReqDto incomeStatReqDto = new IncomeStatReqDto(symbol, 3, FmpPeriod.annual);
        List<IncomeStatResDto> income = incomeStatementService.findIncomeStat(incomeStatReqDto);
        if(income == null || income.size() < 3) {
            result.set결과메시지("영업이익 조회에 실패했습니다.");
            return null;
        } else {
            for(IncomeStatResDto ic : income) {
                if(!ic.getReportedCurrency().equals(CurrencyConst.USA)) {
                    double rate = helper.getForexQuotePriceUSD(ic.getReportedCurrency());
                    if(log.isDebugEnabled()) log.debug("[영업이익 환율 반영] {} -> USD / rate = {} / 변경전 = {}", ic.getReportedCurrency(), rate, ic);
                    if(rate != -1) ic.applyExchangeRate(rate);
                    if(log.isDebugEnabled()) log.debug("[영업이익 환율 반영] {} -> USD / rate = {} / 변경후 = {}", ic.getReportedCurrency(), rate, ic);
                }
            }
        }

        Thread.sleep(TRSC_DELAY);
        BalanceSheetReqDto balanceSheetReqDto = new BalanceSheetReqDto(symbol, 1, FmpPeriod.quarter);
        List<BalanceSheetResDto> balance = balanceSheetStatementService.findBalanceSheet(balanceSheetReqDto);
        if(balance == null || balance.size() < 1) {
            result.set결과메시지("재무상태표 조회에 실패했습니다.");
            return null;
        } else {
            for(BalanceSheetResDto bs : balance) {
                if(!bs.getReportedCurrency().equals(CurrencyConst.USA)) {
                    double rate = helper.getForexQuotePriceUSD(bs.getReportedCurrency());
                    if(log.isDebugEnabled()) log.debug("[재무상태표 환율 반영] {} -> USD / rate = {} / 변경전 = {}", bs.getReportedCurrency(), rate, bs);
                    if(rate != -1) bs.applyExchangeRate(rate);
                    if(log.isDebugEnabled()) log.debug("[재무상태표 환율 반영] {} -> USD / rate = {} / 변경후 = {}", bs.getReportedCurrency(), rate, bs);
                }
            }
        }

        Thread.sleep(TRSC_DELAY);
        EnterpriseValuesReqDto enterpriseValuesReqDto = new EnterpriseValuesReqDto(symbol, 1, FmpPeriod.quarter);
        List<EnterpriseValuesResDto> enterpriseValues = enterpriseValueService.findEnterpriseValue(enterpriseValuesReqDto);
        if(enterpriseValues == null || enterpriseValues.size() < 1) {
            result.set결과메시지("기업가치 조회에 실패했습니다.");
            return null;
        }

        Thread.sleep(TRSC_DELAY);
        FinancialRatiosTTM_ReqDto financialRatiosTTM_ReqDto = new FinancialRatiosTTM_ReqDto(symbol);
        List<FinancialRatiosTTM_ResDto> financialRatios = financialRatiosService.findFinancialRatiosTTM(financialRatiosTTM_ReqDto);
        if(financialRatios == null || financialRatios.size() < 1) {
            result.set결과메시지("재무비율지표(TTM) 조회에 실패했습니다.");
            return null;
        }

        FinancialGrowthReqDto financialGrowthReqDto = new FinancialGrowthReqDto(symbol, 1, FmpPeriod.fiscalYear);
        List<FinancialGrowthResDto> financialGrowth = financialGrowthService.financialStatementsGrowth(financialGrowthReqDto);
        if(financialGrowth == null || financialGrowth.size() < 1) {
            result.set결과메시지("성장률 조회에 실패했습니다.");
            return null;
        }

        IncomeStatGrowthReqDto incomeStatGrowthReqDto = new IncomeStatGrowthReqDto(symbol, 1, FmpPeriod.annual);
        List<IncomeStatGrowthResDto> incomeStatGrowth = incomeStatGrowthService.findIncomeStatGrowth(incomeStatGrowthReqDto);
        if(incomeStatGrowth == null || incomeStatGrowth.size() < 1) {
            result.set결과메시지("영업이익 성장률 조회에 실패했습니다.");
            return null;
        }

        helper.setIncomeStat(calParam, income, resultDetail);
        helper.setPsr(calParam, income, incomeStatGrowth, financialRatios);

        String assetsCurrent = StringUtil.defaultString(balance.get(0).getTotalCurrentAssets());
        calParam.setCurrentAssetsTotal(assetsCurrent);
        resultDetail.set유동자산합계(assetsCurrent);

        String liabilitiesCurrent = StringUtil.defaultString(balance.get(0).getTotalCurrentLiabilities());
        calParam.setCurrentLiabilitiesTotal(liabilitiesCurrent);
        resultDetail.set유동부채합계(liabilitiesCurrent);

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

        String longTermInvestments = StringUtil.defaultString(balance.get(0).getLongTermInvestments());
        calParam.setInvestmentAssets(longTermInvestments);
        resultDetail.set투자자산_비유동자산내(longTermInvestments);

        String intangibleAssets = StringUtil.defaultString(balance.get(0).getGoodwillAndIntangibleAssets());
        calParam.setIntangibleAssets(intangibleAssets);
        resultDetail.set무형자산(intangibleAssets);

        String numberOfShares = StringUtil.defaultString(enterpriseValues.get(0).getNumberOfShares());
        calParam.setIssuedShares(numberOfShares);
        resultDetail.set발행주식수(numberOfShares);

        String per = StringUtil.defaultString(financialRatios.get(0).getPriceToEarningsRatioTTM());
        calParam.setPer(per);
        resultDetail.setPER(per);

        String epsGrowth = StringUtil.defaultString(financialGrowth.get(0).getEpsgrowth());
        calParam.setEpsgrowth(epsGrowth);
        resultDetail.setEPS성장률(epsGrowth);

        String incomeGrowth = StringUtil.defaultString(incomeStatGrowth.get(0).getGrowthOperatingIncome());
        calParam.setOperatingIncomeGrowth(incomeGrowth);
        resultDetail.set영업이익성장률(incomeGrowth);

        helper.setRnDStat(calParam, income, resultDetail);

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

        return calParam;
    }

    private CompanySharePriceCalculator getCalParamDataV6(String symbol, CompanySharePriceResult result, CompanySharePriceResultDetail resultDetail)
            throws InterruptedException {

        // V6: V5 + 분기 영업이익 4개 추가
        CompanySharePriceCalculator calParam = getCalParamDataV5(symbol, result, resultDetail);
        if (calParam == null) return null;

        // 분기 영업이익 4개 조회
        Thread.sleep(TRSC_DELAY);
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
        } else {
            if(log.isDebugEnabled()) log.debug("[V6] {} - 분기 영업이익 데이터 부족 ({}건), 분기 추세 할인 미적용",
                    symbol, quarterlyIncome != null ? quarterlyIncome.size() : 0);
        }

        return calParam;
    }

    private CompanySharePriceCalculator getCalParamDataV7(String symbol, CompanySharePriceResult result, CompanySharePriceResultDetail resultDetail)
            throws InterruptedException {

        // V7: V6 기반 + PBR 추출
        CompanySharePriceCalculator calParam = getCalParamDataV6(symbol, result, resultDetail);
        if (calParam == null) return null;

        // PBR (V7: 그레이엄 스크리닝용) - financialRatios를 다시 조회해야 함
        Thread.sleep(TRSC_DELAY);
        FinancialRatiosTTM_ReqDto financialRatiosTTM_ReqDto = new FinancialRatiosTTM_ReqDto(symbol);
        List<FinancialRatiosTTM_ResDto> financialRatios = financialRatiosService.findFinancialRatiosTTM(financialRatiosTTM_ReqDto);
        if(financialRatios != null && !financialRatios.isEmpty()) {
            Double pbrTTM = financialRatios.get(0).getPriceToBookRatioTTM();
            if (pbrTTM != null) {
                resultDetail.setPBR(String.valueOf(pbrTTM));
            }
        }

        return calParam;
    }
}
