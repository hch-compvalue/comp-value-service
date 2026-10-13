package com.finance.dart.api.common.service;

import com.finance.dart.api.common.constants.RequestContextConst;
import com.finance.dart.api.common.context.RequestContext;
import com.finance.dart.api.common.dto.CompanySharePriceCalculator;
import com.finance.dart.api.common.dto.CompanySharePriceResultDetail;
import com.finance.dart.api.common.dto.SectorCalculationParameters;
import com.finance.dart.common.util.CalUtil;
import com.finance.dart.common.util.StringUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 한 주당 가치 계산 서비스
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class PerShareValueCalculationService {

    private final RequestContext requestContext;

    private final PerShareValueCalcHelper calcHelper;

    /** 주당가치 소수 자릿수 (센트) */
    private static final int PER_SHARE_SCALE = 2;

    /** V9: 유효세율 없을 때 기본세율 (미국 법인세 법정세율) */
    private static final BigDecimal DEFAULT_TAX_RATE_V9 = new BigDecimal("0.21");
    /** V9: 유효세율 상한 (일회성 세금으로 인한 과도한 할인 방지) */
    private static final BigDecimal MAX_TAX_RATE_V9 = new BigDecimal("0.35");
    /**
     * V9: 정상 차입금리 — 이자비용 하한 = 총부채(리스 포함) × 6%
     * 자기자본법은 부채를 이자로만 반영하므로 저금리 차입·리스 부채가 큰 기업이 과대평가됨
     * (백테스트: 고부채+장부이자율<3% 그룹 괴리 중앙값 V8 -52% → V9 +15%). 6% ≈ 미국 BBB 회사채 장기금리,
     * 0%→6% 에서 PER경로 IC12 0.036→0.051, 연도별 7/8 양수 (8% 이상은 안정성 저하)
     */
    private static final BigDecimal NORMALIZED_DEBT_RATE_V9 = new BigDecimal("0.06");

    /**
     * V8: 적정가 계산
     * - PER 블렌딩: min(actualPER, sectorPER×1.5)×0.6 + sectorPER×0.4
     * - 고성장률 지속가능성 할인: 50% 초과분은 50%만 인정
     * - adjustedPER 상한: sectorPER × 2.0
     */
    public String calPerValueV8(CompanySharePriceCalculator req, CompanySharePriceResultDetail resultDetail, String sector) {

        if(log.isDebugEnabled()) log.debug("[V8] CompanySharePriceCalculator = {}", req);
        if(log.isDebugEnabled()) log.debug("[V8] Sector = {}", sector);

        SectorCalculationParameters sectorParams =
                SectorParameterFactory.getParameters(sector);

        // PBR 기반 평가 라우팅 (Financial Services)
        if (sectorParams.isUsePbrValuation()
                && !StringUtil.isStringEmpty(req.getBookValuePerShare())
                && !StringUtil.isStringEmpty(req.getRoe())) {
            return calPerValueV8_Financial(req, resultDetail, sector, sectorParams);
        }

        final String per = req.getPer();
        final String incomGrowth = req.getOperatingIncomeGrowth();
        final String epsGrowth = req.getEpsgrowth();
        final String assetsTotal = req.getCurrentAssetsTotal();
        final String liabilitiesTotal = req.getCurrentLiabilitiesTotal();
        final String intangibleAssets = req.getIntangibleAssets();
        final String totalDebt = req.getTotalDebt();
        final String cash = req.getCashAndCashEquivalents();
        final String currentRatio = req.getCurrentRatio();
        final String investmentAssets = req.getInvestmentAssets();
        final String issuedShares = req.getIssuedShares();

        // 유동부채 차감 비율
        final String K;
        if (sectorParams.isApplyCurrentRatio()) {
            K = StringUtil.defaultString(calcHelper.getLiabilityFactor(Double.parseDouble(currentRatio)));
        } else {
            K = "0";
        }

        // =====================================================================
        // 영업이익 연간 추세 팩터 계산 (V7과 동일)
        // =====================================================================
        BigDecimal annualTrendFactor = BigDecimal.ONE;
        BigDecimal curProfit = new BigDecimal(req.getOperatingProfitCurrent());
        BigDecimal preProfit = new BigDecimal(req.getOperatingProfitPre());
        BigDecimal prePreProfit = new BigDecimal(req.getOperatingProfitPrePre());

        if (curProfit.compareTo(preProfit) < 0 && preProfit.compareTo(prePreProfit) < 0) {
            annualTrendFactor = new BigDecimal("0.8");
            resultDetail.set연속하락추세(true);
        } else if (curProfit.compareTo(preProfit) < 0) {
            annualTrendFactor = new BigDecimal("0.9");
            resultDetail.set단일하락추세(true);
        } else if (curProfit.compareTo(preProfit) > 0 && preProfit.compareTo(prePreProfit) > 0) {
            resultDetail.set연속상승추세(true);
        }
        resultDetail.set영업이익추세팩터(annualTrendFactor.toPlainString());

        // STEP01 ------------------------------------------------------------------------------------------------------
        // V8: 가중평균 (1:2:3) 적용 (V7과 동일)
        String operatingProfitAvg = calcHelper.calOperatingProfitWeightedAvg(
                req.getOperatingProfitPrePre(),
                req.getOperatingProfitPre(),
                req.getOperatingProfitCurrent()
        );

        // 영업이익 평균에 연간 추세팩터 적용
        operatingProfitAvg = CalUtil.multi(operatingProfitAvg, annualTrendFactor.toPlainString());

        String adjustedPER;
        String STEP01;

        BigDecimal perVal = new BigDecimal(per);
        BigDecimal sectorPER = sectorParams.getBasePER();

        if (perVal.signum() <= 0 || perVal.compareTo(new BigDecimal("100")) > 0) {
            BigDecimal revenueGrowthVal = new BigDecimal(req.getRevenueGrowth());

            if(perVal.signum() <= 0) {
                resultDetail.set적자기업(true);
            }

            if (revenueGrowthVal.compareTo(new BigDecimal("0.2")) > 0) {
                String revenue = req.getRevenue();
                String psr = req.getPsr();
                BigDecimal psrVal = new BigDecimal(psr);

                BigDecimal maxPsr = sectorParams.getMaxPSR();
                if(psrVal.compareTo(maxPsr) > 0) {
                    psr = maxPsr.toPlainString();
                }

                String growthFactor = calcHelper.getRevenueGrowthFactor(revenueGrowthVal);

                adjustedPER = "0";
                STEP01 = CalUtil.multi(CalUtil.multi(revenue, psr), growthFactor);

                // PSR 경로 STEP01 상한: 시가총액 × 3 초과 방지
                if (req.getPrice() > 0 && !StringUtil.isStringEmpty(issuedShares)) {
                    BigDecimal marketCap = BigDecimal.valueOf(req.getPrice()).multiply(new BigDecimal(issuedShares));
                    BigDecimal step01Val = new BigDecimal(STEP01);
                    BigDecimal maxStep01 = marketCap.multiply(new BigDecimal("3"));
                    if (marketCap.compareTo(BigDecimal.ZERO) > 0 && step01Val.compareTo(maxStep01) > 0) {
                        log.debug("[V8] PSR경로 STEP01 시가총액 상한 적용: {} → {}", step01Val, maxStep01.setScale(0, RoundingMode.HALF_UP));
                        STEP01 = maxStep01.setScale(2, RoundingMode.HALF_UP).toPlainString();
                    }
                }

                resultDetail.set매출기반평가(true);
                resultDetail.set매출액(revenue);
                resultDetail.setPSR(psr);
                resultDetail.set매출성장률(revenueGrowthVal.toPlainString());
                resultDetail.set매출성장률보정계수(growthFactor);

                resultDetail.set실제PER(per);
                resultDetail.set섹터PER(sectorPER.toPlainString());
                resultDetail.set블렌딩PER("N/A (PSR경로)");

            } else {
                adjustedPER = "0";
                STEP01 = "0";
                resultDetail.set수익가치계산불가(true);

                resultDetail.set실제PER(per);
                resultDetail.set섹터PER(sectorPER.toPlainString());
                resultDetail.set블렌딩PER("N/A (자산가치경로)");
            }

        } else {
            BigDecimal preProfitVal = new BigDecimal(req.getOperatingProfitPre());
            BigDecimal curProfitVal = new BigDecimal(req.getOperatingProfitCurrent());
            BigDecimal g = new BigDecimal(incomGrowth);

            if (preProfitVal.signum() < 0 && curProfitVal.signum() > 0) {
                resultDetail.set흑자전환기업(true);

                // V8 보수화: cappedActualPER = min(actualPER, sectorPER×1.5)
                BigDecimal cappedPER = perVal.min(sectorPER.multiply(new BigDecimal("1.5")));
                BigDecimal blendedPER = cappedPER.multiply(new BigDecimal("0.6"))
                        .add(sectorPER.multiply(new BigDecimal("0.4")));

                log.debug("[V8] 흑자전환 PER 블렌딩: 실제PER={}, 섹터PER={}, 캡(×1.5)={}, 캡적용PER={}, 블렌딩PER={}",
                        perVal.setScale(4, RoundingMode.HALF_UP),
                        sectorPER,
                        sectorPER.multiply(new BigDecimal("1.5")).setScale(2, RoundingMode.HALF_UP),
                        cappedPER.setScale(4, RoundingMode.HALF_UP),
                        blendedPER.setScale(4, RoundingMode.HALF_UP));

                // V8: 고성장률 할인 (50% 초과분은 50%만 인정)
                BigDecimal gCapped = applyGrowthDiscount(g, sectorParams.getGrowthRateCap());
                adjustedPER = CalUtil.multi(blendedPER.toPlainString(), CalUtil.add("1", gCapped.toPlainString()));

                resultDetail.set실제PER(per);
                resultDetail.set섹터PER(sectorPER.toPlainString());
                resultDetail.set블렌딩PER(blendedPER.setScale(4, RoundingMode.HALF_UP).toPlainString());

            } else if (preProfitVal.signum() < 0 && curProfitVal.signum() < 0) {
                adjustedPER = sectorPER.toPlainString();

                resultDetail.set실제PER(per);
                resultDetail.set섹터PER(sectorPER.toPlainString());
                resultDetail.set블렌딩PER("N/A (연속적자)");

            } else {
                // V8 보수화: cappedActualPER = min(actualPER, sectorPER×1.5)
                BigDecimal cappedPER = perVal.min(sectorPER.multiply(new BigDecimal("1.5")));
                BigDecimal blendedPER = cappedPER.multiply(new BigDecimal("0.6"))
                        .add(sectorPER.multiply(new BigDecimal("0.4")));

                log.debug("[V8] PER 블렌딩: 실제PER={}, 섹터PER={}, 캡(×1.5)={}, 캡적용PER={}, 블렌딩PER={}",
                        perVal.setScale(4, RoundingMode.HALF_UP),
                        sectorPER,
                        sectorPER.multiply(new BigDecimal("1.5")).setScale(2, RoundingMode.HALF_UP),
                        cappedPER.setScale(4, RoundingMode.HALF_UP),
                        blendedPER.setScale(4, RoundingMode.HALF_UP));

                // V8: 고성장률 할인 (50% 초과분은 50%만 인정)
                BigDecimal gCapped = applyGrowthDiscount(g, sectorParams.getGrowthRateCap());
                adjustedPER = CalUtil.multi(blendedPER.toPlainString(), CalUtil.add("1", gCapped.toPlainString()));

                resultDetail.set실제PER(per);
                resultDetail.set섹터PER(sectorPER.toPlainString());
                resultDetail.set블렌딩PER(blendedPER.setScale(4, RoundingMode.HALF_UP).toPlainString());
            }

            // V8: adjustedPER 상한 (섹터 기준 PER의 2.0배)
            BigDecimal adjustedPERVal = new BigDecimal(adjustedPER);
            BigDecimal maxAdjustedPER = sectorPER.multiply(new BigDecimal("2.0"));
            if (adjustedPERVal.compareTo(maxAdjustedPER) > 0) {
                adjustedPER = maxAdjustedPER.setScale(4, RoundingMode.HALF_UP).toPlainString();
            }

            STEP01 = CalUtil.multi(operatingProfitAvg, adjustedPER);
        }

        // =====================================================================
        // 분기 추세 할인 (V7과 동일)
        // =====================================================================
        BigDecimal quarterlyTrendFactor = BigDecimal.ONE;
        String q1Str = req.getQuarterlyOpIncomeQ1();
        String q2Str = req.getQuarterlyOpIncomeQ2();
        String q3Str = req.getQuarterlyOpIncomeQ3();
        String q4Str = req.getQuarterlyOpIncomeQ4();

        if (!StringUtil.isStringEmpty(q1Str) && !StringUtil.isStringEmpty(q2Str)
                && !StringUtil.isStringEmpty(q3Str) && !StringUtil.isStringEmpty(q4Str)) {

            BigDecimal q1 = new BigDecimal(q1Str);
            BigDecimal q2 = new BigDecimal(q2Str);
            BigDecimal q3 = new BigDecimal(q3Str);
            BigDecimal q4 = new BigDecimal(q4Str);

            BigDecimal recentHalf = q1.add(q2);
            BigDecimal olderHalf = q3.add(q4);

            if (q1.compareTo(q2) < 0 && recentHalf.compareTo(olderHalf) < 0) {
                quarterlyTrendFactor = new BigDecimal("0.7");
                resultDetail.set분기실적악화(true);
            } else if (q1.compareTo(q2) < 0) {
                quarterlyTrendFactor = new BigDecimal("0.85");
                resultDetail.set분기실적악화(true);
            }

            // 최근 분기 적자전환 감지 (Q1 < 0 && Q2 > 0)
            if (q1.signum() < 0 && q2.signum() > 0) {
                quarterlyTrendFactor = quarterlyTrendFactor.multiply(new BigDecimal("0.5"));
                resultDetail.set분기적자전환(true);
            }

            resultDetail.set분기영업이익_Q1(q1Str);
            resultDetail.set분기영업이익_Q2(q2Str);
            resultDetail.set분기영업이익_Q3(q3Str);
            resultDetail.set분기영업이익_Q4(q4Str);
        }

        // 추세팩터 복합 하한 0.5: 연간×분기 곱이 0.5 미만이면 분기팩터 상향 조정
        BigDecimal minQuarterlyForFloor = new BigDecimal("0.5")
                .divide(annualTrendFactor, 4, RoundingMode.HALF_UP);
        if (quarterlyTrendFactor.compareTo(minQuarterlyForFloor) < 0) {
            log.debug("[V8] 추세팩터 하한 적용: 연간={}, 분기 {}→{}", annualTrendFactor, quarterlyTrendFactor, minQuarterlyForFloor);
            quarterlyTrendFactor = minQuarterlyForFloor;
        }

        resultDetail.set분기추세팩터(quarterlyTrendFactor.toPlainString());

        // STEP01에 분기 추세팩터 적용
        STEP01 = CalUtil.multi(STEP01, quarterlyTrendFactor.toPlainString());

        // 수익가치 하한 0: 음수 영업이익 × PER 이 자산가치를 깎거나, 음수에 추세할인(<1)을 곱해
        // 실적이 악화될수록 적정가가 오르는 역전 방지 → 수익가치 없음 = 자산가치만으로 평가
        if (new BigDecimal(STEP01).signum() < 0) {
            log.debug("[V8] 수익가치 음수 → 0 하한 적용: {}", STEP01);
            STEP01 = "0";
        }

        resultDetail.setPER(per);
        resultDetail.set영업이익성장률(incomGrowth);
        resultDetail.set성장률보정PER(adjustedPER);
        resultDetail.setPEG(calcHelper.calPeg(per, epsGrowth));

        // STEP02
        final String STEP02 = CalUtil.add(CalUtil.sub(assetsTotal, CalUtil.multi(liabilitiesTotal, K)), investmentAssets);

        // STEP03
        BigDecimal intangibleWeight = sectorParams.getIntangibleAssetWeight();
        final String STEP03 = CalUtil.multi(intangibleAssets, intangibleWeight.toPlainString());

        // STEP04 (R&D 참고용)
        String rndAvg = calcHelper.calRnDAvg(req.getRndPrePre(), req.getRndPre(), req.getRndCurrent());
        resultDetail.set연구개발비_평균(rndAvg);

        // STEP05
        String netDebt = CalUtil.sub(totalDebt, cash);
        final String STEP05 = netDebt;
        resultDetail.set순부채(netDebt);

        // 계산
        String rst01 = CalUtil.add(STEP01, STEP02);
        String rst02 = CalUtil.add(rst01, STEP03);
        String rst04 = CalUtil.sub(rst02, STEP05);
        String result = divideToPerShare(rst04, issuedShares);

        if(log.isDebugEnabled()) {
            log.debug("[V8 계산] 섹터:{}, 연간추세:{}, 분기추세:{}, STEP01:{}, STEP02:{}, STEP03:{}, STEP05:{}, 결과:{}",
                    sector, annualTrendFactor, quarterlyTrendFactor, STEP01, STEP02, STEP03, STEP05, result);
        }

        return result;
    }

    /**
     * V8: PBR 기반 적정가 계산 (Financial Services 전용)
     * 공식: BPS × targetPBR × 추세팩터
     * - COE = riskFreeRate + Beta × marketRiskPremium
     * - targetPBR = ROE / COE (서브섹터별 상한 적용)
     * - 추세팩터: 연속하락 0.85, 단일하락 0.92, 그 외 1.0
     * - 순부채 차감 없음 (BPS가 이미 순자산 = 자산-부채 반영)
     * - ROE cap: 서브섹터별 (은행 18%, 보험 20%, 기타 25%)
     * - targetPBR 상한: 서브섹터별 (은행 1.8, 보험 2.0, 기타 2.5)
     */
    String calPerValueV8_Financial(CompanySharePriceCalculator req, CompanySharePriceResultDetail resultDetail,
                                          String sector, SectorCalculationParameters sectorParams) {

        resultDetail.setPBR기반평가(true);

        String industry = req.getIndustry();
        BigDecimal bps = new BigDecimal(req.getBookValuePerShare());
        BigDecimal roe = new BigDecimal(req.getRoe());

        // 서브섹터별 ROE cap & targetPBR 상한 결정
        BigDecimal roeCap = sectorParams.getRoeCap() != null ? sectorParams.getRoeCap() : new BigDecimal("0.20");
        BigDecimal subSectorMaxTargetPBR = sectorParams.getMaxTargetPBR();

        if (!StringUtil.isStringEmpty(industry)) {
            String ind = industry.toLowerCase();
            if (ind.contains("bank")) {
                // 은행: 레버리지가 높아 ROE가 부풀려짐, 보수적 평가
                roeCap = new BigDecimal("0.18");
                subSectorMaxTargetPBR = new BigDecimal("1.8");
            } else if (ind.contains("insurance")) {
                // 보험: 은행보다 레버리지 낮지만 여전히 보수적
                roeCap = new BigDecimal("0.20");
                subSectorMaxTargetPBR = new BigDecimal("2.0");
            } else {
                // Capital Markets, Credit Services, FinTech 등
                roeCap = new BigDecimal("0.25");
                subSectorMaxTargetPBR = new BigDecimal("2.5");
            }
        }

        // ROE cap 적용 (일시적 고ROE로 인한 과대평가 방지)
        BigDecimal cappedRoe = roe.min(roeCap);

        // Beta (기본값 1.0)
        BigDecimal beta = BigDecimal.ONE;
        if (!StringUtil.isStringEmpty(req.getBeta())) {
            try {
                beta = new BigDecimal(req.getBeta());
                if (beta.compareTo(BigDecimal.ZERO) <= 0) beta = BigDecimal.ONE;
            } catch (Exception e) {
                beta = BigDecimal.ONE;
            }
        }

        // COE = riskFreeRate + Beta × marketRiskPremium
        BigDecimal coe = sectorParams.getRiskFreeRate().add(
                beta.multiply(sectorParams.getMarketRiskPremium()));
        resultDetail.setCOE(coe.setScale(4, RoundingMode.HALF_UP).toPlainString());

        // targetPBR = cappedROE / COE (서브섹터별 범위 제한)
        BigDecimal targetPBR;
        if (coe.compareTo(BigDecimal.ZERO) <= 0) {
            targetPBR = sectorParams.getMinTargetPBR();
        } else {
            targetPBR = cappedRoe.divide(coe, 4, RoundingMode.HALF_UP);
        }
        targetPBR = targetPBR.max(sectorParams.getMinTargetPBR()).min(subSectorMaxTargetPBR);
        resultDetail.setTargetPBR(targetPBR.setScale(4, RoundingMode.HALF_UP).toPlainString());

        // 연간 추세팩터
        BigDecimal trendFactor = BigDecimal.ONE;
        BigDecimal curProfit = new BigDecimal(req.getOperatingProfitCurrent());
        BigDecimal preProfit = new BigDecimal(req.getOperatingProfitPre());
        BigDecimal prePreProfit = new BigDecimal(req.getOperatingProfitPrePre());

        if (curProfit.compareTo(preProfit) < 0 && preProfit.compareTo(prePreProfit) < 0) {
            trendFactor = new BigDecimal("0.85");
            resultDetail.set연속하락추세(true);
        } else if (curProfit.compareTo(preProfit) < 0) {
            trendFactor = new BigDecimal("0.92");
            resultDetail.set단일하락추세(true);
        } else if (curProfit.compareTo(preProfit) > 0 && preProfit.compareTo(prePreProfit) > 0) {
            resultDetail.set연속상승추세(true);
        }
        resultDetail.set영업이익추세팩터(trendFactor.toPlainString());

        // 적정가 = BPS × targetPBR × 추세팩터
        // ※ 순부채 차감 제거: BPS = (총자산-총부채)/주식수 이므로 이미 부채 반영됨 (이중 차감 방지)
        BigDecimal fairValue = bps.multiply(targetPBR).multiply(trendFactor)
                .setScale(2, RoundingMode.HALF_UP);

        // PBR 기반이므로 PER 관련 resultDetail도 세팅
        resultDetail.set실제PER(req.getPer() != null ? req.getPer() : "N/A");
        resultDetail.set섹터PER(sectorParams.getBasePER().toPlainString());
        resultDetail.set블렌딩PER("N/A (PBR경로)");
        resultDetail.set성장률보정PER("N/A (PBR경로)");
        resultDetail.setPEG(calcHelper.calPeg(req.getPer(), req.getEpsgrowth()));

        // 순부채 정보는 참고용으로만 기록 (적정가 차감하지 않음)
        String totalDebt = req.getTotalDebt();
        String cash = req.getCashAndCashEquivalents();
        if (!StringUtil.isStringEmpty(totalDebt) && !StringUtil.isStringEmpty(cash)) {
            BigDecimal netDebt = new BigDecimal(totalDebt).subtract(new BigDecimal(cash));
            resultDetail.set순부채(netDebt.toPlainString());
        }

        if(log.isDebugEnabled()) {
            log.debug("[V8 Financial PBR] 섹터:{}, 산업:{}, BPS:{}, ROE:{} (cap:{}→{}), Beta:{}, COE:{}, targetPBR:{} (max:{}), 추세:{}, 적정가:{}",
                    sector, industry, bps, roe, roeCap, cappedRoe, beta, coe, targetPBR, subSectorMaxTargetPBR, trendFactor, fairValue);
        }

        return fairValue.toPlainString();
    }

    /**
     * V9: 적정가 계산 (자기자본법)
     * - PER 경로: (영업이익 가중평균×연간추세 − 이자비용) × (1 − 세율) × adjustedPER × 분기추세 + 현금 + 투자자산
     *   · PER 은 순이익 기준 배수이므로 세전·이자전 영업이익에 바로 곱하던 V8 의 과대평가(+27% 내외) 제거
     *   · 세후이익×PER 은 이미 주주가치 → 순부채 차감 없음(부채는 이자비용으로 반영), 운전자본·영업권 중복가산 제거
     * - PSR/자산가치 경로: V8 골격 유지 + 현금 중복가산 제거(유동자산에서 현금 제외), 리스 중복차감 제거(getCalParamDataV9)
     * - PER 블렌딩/성장률 할인/adjustedPER 상한/추세팩터/STEP01 하한 0 은 V8 과 동일
     * - 근거: test/backtest-v8-2026-10 (2018~2025 백테스트, PER경로 IC12 0.012 → 0.037)
     */
    public String calPerValueV9(CompanySharePriceCalculator req, CompanySharePriceResultDetail resultDetail, String sector) {

        if(log.isDebugEnabled()) log.debug("[V9] CompanySharePriceCalculator = {}", req);
        if(log.isDebugEnabled()) log.debug("[V9] Sector = {}", sector);

        SectorCalculationParameters sectorParams =
                SectorParameterFactory.getParameters(sector);

        // PBR 기반 평가 라우팅 (Financial Services)
        if (sectorParams.isUsePbrValuation()
                && !StringUtil.isStringEmpty(req.getBookValuePerShare())
                && !StringUtil.isStringEmpty(req.getRoe())) {
            return calPerValueV9_Financial(req, resultDetail, sector, sectorParams);
        }

        final String per = req.getPer();
        final String incomGrowth = req.getOperatingIncomeGrowth();
        final String epsGrowth = req.getEpsgrowth();
        final String assetsTotal = req.getCurrentAssetsTotal();
        final String liabilitiesTotal = req.getCurrentLiabilitiesTotal();
        final String intangibleAssets = req.getIntangibleAssets();
        final String totalDebt = req.getTotalDebt();
        final String cash = req.getCashAndCashEquivalents();
        final String currentRatio = req.getCurrentRatio();
        final String investmentAssets = req.getInvestmentAssets();
        final String issuedShares = req.getIssuedShares();

        // 유동부채 차감 비율
        final String K;
        if (sectorParams.isApplyCurrentRatio()) {
            K = StringUtil.defaultString(calcHelper.getLiabilityFactor(Double.parseDouble(currentRatio)));
        } else {
            K = "0";
        }

        // =====================================================================
        // 영업이익 연간 추세 팩터 계산 (V7과 동일)
        // =====================================================================
        BigDecimal annualTrendFactor = BigDecimal.ONE;
        BigDecimal curProfit = new BigDecimal(req.getOperatingProfitCurrent());
        BigDecimal preProfit = new BigDecimal(req.getOperatingProfitPre());
        BigDecimal prePreProfit = new BigDecimal(req.getOperatingProfitPrePre());

        if (curProfit.compareTo(preProfit) < 0 && preProfit.compareTo(prePreProfit) < 0) {
            annualTrendFactor = new BigDecimal("0.8");
            resultDetail.set연속하락추세(true);
        } else if (curProfit.compareTo(preProfit) < 0) {
            annualTrendFactor = new BigDecimal("0.9");
            resultDetail.set단일하락추세(true);
        } else if (curProfit.compareTo(preProfit) > 0 && preProfit.compareTo(prePreProfit) > 0) {
            resultDetail.set연속상승추세(true);
        }
        resultDetail.set영업이익추세팩터(annualTrendFactor.toPlainString());

        // STEP01 ------------------------------------------------------------------------------------------------------
        // V8: 가중평균 (1:2:3) 적용 (V7과 동일)
        String operatingProfitAvg = calcHelper.calOperatingProfitWeightedAvg(
                req.getOperatingProfitPrePre(),
                req.getOperatingProfitPre(),
                req.getOperatingProfitCurrent()
        );

        // 영업이익 평균에 연간 추세팩터 적용
        operatingProfitAvg = CalUtil.multi(operatingProfitAvg, annualTrendFactor.toPlainString());

        String adjustedPER;
        String STEP01;
        boolean equityMethod = false;   // PER 경로(세후이익×PER) 여부 → 최종 합산식 분기

        BigDecimal perVal = new BigDecimal(per);
        BigDecimal sectorPER = sectorParams.getBasePER();

        if (perVal.signum() <= 0 || perVal.compareTo(new BigDecimal("100")) > 0) {
            BigDecimal revenueGrowthVal = new BigDecimal(req.getRevenueGrowth());

            if(perVal.signum() <= 0) {
                resultDetail.set적자기업(true);
            }

            if (revenueGrowthVal.compareTo(new BigDecimal("0.2")) > 0) {
                String revenue = req.getRevenue();
                String psr = req.getPsr();
                BigDecimal psrVal = new BigDecimal(psr);

                BigDecimal maxPsr = sectorParams.getMaxPSR();
                if(psrVal.compareTo(maxPsr) > 0) {
                    psr = maxPsr.toPlainString();
                }

                String growthFactor = calcHelper.getRevenueGrowthFactor(revenueGrowthVal);

                adjustedPER = "0";
                STEP01 = CalUtil.multi(CalUtil.multi(revenue, psr), growthFactor);

                // PSR 경로 STEP01 상한: 시가총액 × 3 초과 방지
                if (req.getPrice() > 0 && !StringUtil.isStringEmpty(issuedShares)) {
                    BigDecimal marketCap = BigDecimal.valueOf(req.getPrice()).multiply(new BigDecimal(issuedShares));
                    BigDecimal step01Val = new BigDecimal(STEP01);
                    BigDecimal maxStep01 = marketCap.multiply(new BigDecimal("3"));
                    if (marketCap.compareTo(BigDecimal.ZERO) > 0 && step01Val.compareTo(maxStep01) > 0) {
                        log.debug("[V9] PSR경로 STEP01 시가총액 상한 적용: {} → {}", step01Val, maxStep01.setScale(0, RoundingMode.HALF_UP));
                        STEP01 = maxStep01.setScale(2, RoundingMode.HALF_UP).toPlainString();
                    }
                }

                resultDetail.set매출기반평가(true);
                resultDetail.set매출액(revenue);
                resultDetail.setPSR(psr);
                resultDetail.set매출성장률(revenueGrowthVal.toPlainString());
                resultDetail.set매출성장률보정계수(growthFactor);

                resultDetail.set실제PER(per);
                resultDetail.set섹터PER(sectorPER.toPlainString());
                resultDetail.set블렌딩PER("N/A (PSR경로)");

            } else {
                adjustedPER = "0";
                STEP01 = "0";
                resultDetail.set수익가치계산불가(true);

                resultDetail.set실제PER(per);
                resultDetail.set섹터PER(sectorPER.toPlainString());
                resultDetail.set블렌딩PER("N/A (자산가치경로)");
            }

        } else {
            BigDecimal preProfitVal = new BigDecimal(req.getOperatingProfitPre());
            BigDecimal curProfitVal = new BigDecimal(req.getOperatingProfitCurrent());
            BigDecimal g = new BigDecimal(incomGrowth);

            if (preProfitVal.signum() < 0 && curProfitVal.signum() > 0) {
                resultDetail.set흑자전환기업(true);

                // V8 보수화: cappedActualPER = min(actualPER, sectorPER×1.5)
                BigDecimal cappedPER = perVal.min(sectorPER.multiply(new BigDecimal("1.5")));
                BigDecimal blendedPER = cappedPER.multiply(new BigDecimal("0.6"))
                        .add(sectorPER.multiply(new BigDecimal("0.4")));

                log.debug("[V9] 흑자전환 PER 블렌딩: 실제PER={}, 섹터PER={}, 캡(×1.5)={}, 캡적용PER={}, 블렌딩PER={}",
                        perVal.setScale(4, RoundingMode.HALF_UP),
                        sectorPER,
                        sectorPER.multiply(new BigDecimal("1.5")).setScale(2, RoundingMode.HALF_UP),
                        cappedPER.setScale(4, RoundingMode.HALF_UP),
                        blendedPER.setScale(4, RoundingMode.HALF_UP));

                // V8: 고성장률 할인 (50% 초과분은 50%만 인정)
                BigDecimal gCapped = applyGrowthDiscount(g, sectorParams.getGrowthRateCap());
                adjustedPER = CalUtil.multi(blendedPER.toPlainString(), CalUtil.add("1", gCapped.toPlainString()));

                resultDetail.set실제PER(per);
                resultDetail.set섹터PER(sectorPER.toPlainString());
                resultDetail.set블렌딩PER(blendedPER.setScale(4, RoundingMode.HALF_UP).toPlainString());

            } else if (preProfitVal.signum() < 0 && curProfitVal.signum() < 0) {
                adjustedPER = sectorPER.toPlainString();

                resultDetail.set실제PER(per);
                resultDetail.set섹터PER(sectorPER.toPlainString());
                resultDetail.set블렌딩PER("N/A (연속적자)");

            } else {
                // V8 보수화: cappedActualPER = min(actualPER, sectorPER×1.5)
                BigDecimal cappedPER = perVal.min(sectorPER.multiply(new BigDecimal("1.5")));
                BigDecimal blendedPER = cappedPER.multiply(new BigDecimal("0.6"))
                        .add(sectorPER.multiply(new BigDecimal("0.4")));

                log.debug("[V9] PER 블렌딩: 실제PER={}, 섹터PER={}, 캡(×1.5)={}, 캡적용PER={}, 블렌딩PER={}",
                        perVal.setScale(4, RoundingMode.HALF_UP),
                        sectorPER,
                        sectorPER.multiply(new BigDecimal("1.5")).setScale(2, RoundingMode.HALF_UP),
                        cappedPER.setScale(4, RoundingMode.HALF_UP),
                        blendedPER.setScale(4, RoundingMode.HALF_UP));

                // V8: 고성장률 할인 (50% 초과분은 50%만 인정)
                BigDecimal gCapped = applyGrowthDiscount(g, sectorParams.getGrowthRateCap());
                adjustedPER = CalUtil.multi(blendedPER.toPlainString(), CalUtil.add("1", gCapped.toPlainString()));

                resultDetail.set실제PER(per);
                resultDetail.set섹터PER(sectorPER.toPlainString());
                resultDetail.set블렌딩PER(blendedPER.setScale(4, RoundingMode.HALF_UP).toPlainString());
            }

            // V8: adjustedPER 상한 (섹터 기준 PER의 2.0배)
            BigDecimal adjustedPERVal = new BigDecimal(adjustedPER);
            BigDecimal maxAdjustedPER = sectorPER.multiply(new BigDecimal("2.0"));
            if (adjustedPERVal.compareTo(maxAdjustedPER) > 0) {
                adjustedPER = maxAdjustedPER.setScale(4, RoundingMode.HALF_UP).toPlainString();
            }

            // V9 자기자본법: PER(순이익 배수)에 맞춰 이자·세금 차감 후 곱함
            String afterTaxEarnings = calAfterTaxEarningsV9(operatingProfitAvg, req, resultDetail);
            STEP01 = CalUtil.multi(afterTaxEarnings, adjustedPER);
            equityMethod = true;
        }

        // =====================================================================
        // 분기 추세 할인 (V7과 동일)
        // =====================================================================
        BigDecimal quarterlyTrendFactor = BigDecimal.ONE;
        String q1Str = req.getQuarterlyOpIncomeQ1();
        String q2Str = req.getQuarterlyOpIncomeQ2();
        String q3Str = req.getQuarterlyOpIncomeQ3();
        String q4Str = req.getQuarterlyOpIncomeQ4();

        if (!StringUtil.isStringEmpty(q1Str) && !StringUtil.isStringEmpty(q2Str)
                && !StringUtil.isStringEmpty(q3Str) && !StringUtil.isStringEmpty(q4Str)) {

            BigDecimal q1 = new BigDecimal(q1Str);
            BigDecimal q2 = new BigDecimal(q2Str);
            BigDecimal q3 = new BigDecimal(q3Str);
            BigDecimal q4 = new BigDecimal(q4Str);

            BigDecimal recentHalf = q1.add(q2);
            BigDecimal olderHalf = q3.add(q4);

            if (q1.compareTo(q2) < 0 && recentHalf.compareTo(olderHalf) < 0) {
                quarterlyTrendFactor = new BigDecimal("0.7");
                resultDetail.set분기실적악화(true);
            } else if (q1.compareTo(q2) < 0) {
                quarterlyTrendFactor = new BigDecimal("0.85");
                resultDetail.set분기실적악화(true);
            }

            // 최근 분기 적자전환 감지 (Q1 < 0 && Q2 > 0)
            if (q1.signum() < 0 && q2.signum() > 0) {
                quarterlyTrendFactor = quarterlyTrendFactor.multiply(new BigDecimal("0.5"));
                resultDetail.set분기적자전환(true);
            }

            resultDetail.set분기영업이익_Q1(q1Str);
            resultDetail.set분기영업이익_Q2(q2Str);
            resultDetail.set분기영업이익_Q3(q3Str);
            resultDetail.set분기영업이익_Q4(q4Str);
        }

        // 추세팩터 복합 하한 0.5: 연간×분기 곱이 0.5 미만이면 분기팩터 상향 조정
        BigDecimal minQuarterlyForFloor = new BigDecimal("0.5")
                .divide(annualTrendFactor, 4, RoundingMode.HALF_UP);
        if (quarterlyTrendFactor.compareTo(minQuarterlyForFloor) < 0) {
            log.debug("[V9] 추세팩터 하한 적용: 연간={}, 분기 {}→{}", annualTrendFactor, quarterlyTrendFactor, minQuarterlyForFloor);
            quarterlyTrendFactor = minQuarterlyForFloor;
        }

        resultDetail.set분기추세팩터(quarterlyTrendFactor.toPlainString());

        // STEP01에 분기 추세팩터 적용
        STEP01 = CalUtil.multi(STEP01, quarterlyTrendFactor.toPlainString());

        // 수익가치 하한 0: 음수 영업이익 × PER 이 자산가치를 깎거나, 음수에 추세할인(<1)을 곱해
        // 실적이 악화될수록 적정가가 오르는 역전 방지 → 수익가치 없음 = 자산가치만으로 평가
        if (new BigDecimal(STEP01).signum() < 0) {
            log.debug("[V9] 수익가치 음수 → 0 하한 적용: {}", STEP01);
            STEP01 = "0";
        }

        resultDetail.setPER(per);
        resultDetail.set영업이익성장률(incomGrowth);
        resultDetail.set성장률보정PER(adjustedPER);
        resultDetail.setPEG(calcHelper.calPeg(per, epsGrowth));

        // STEP04 (R&D 참고용)
        String rndAvg = calcHelper.calRnDAvg(req.getRndPrePre(), req.getRndPre(), req.getRndCurrent());
        resultDetail.set연구개발비_평균(rndAvg);

        String netDebt = CalUtil.sub(totalDebt, cash);
        resultDetail.set순부채(netDebt);

        String STEP02 = "0";
        String STEP03 = "0";
        String result;
        if (equityMethod) {
            // 주주가치 = 세후수익가치 + 비영업자산(현금 + 투자자산), 부채 차감 없음
            resultDetail.set세후수익가치(STEP01);
            String equityValue = CalUtil.add(CalUtil.add(STEP01, cash), investmentAssets);
            result = divideToPerShare(equityValue, issuedShares);
        } else {
            // 매출/자산가치 경로: 유동자산에서 현금 제외(순부채에서 이미 현금 반영)
            STEP02 = CalUtil.add(CalUtil.sub(CalUtil.sub(assetsTotal, cash), CalUtil.multi(liabilitiesTotal, K)), investmentAssets);
            BigDecimal intangibleWeight = sectorParams.getIntangibleAssetWeight();
            STEP03 = CalUtil.multi(intangibleAssets, intangibleWeight.toPlainString());
            String enterpriseValue = CalUtil.sub(CalUtil.add(CalUtil.add(STEP01, STEP02), STEP03), netDebt);
            result = divideToPerShare(enterpriseValue, issuedShares);
        }

        if(log.isDebugEnabled()) {
            log.debug("[V9 계산] 섹터:{}, 자기자본법:{}, 연간추세:{}, 분기추세:{}, STEP01:{}, STEP02:{}, STEP03:{}, 순부채:{}, 결과:{}",
                    sector, equityMethod, annualTrendFactor, quarterlyTrendFactor, STEP01, STEP02, STEP03, netDebt, result);
        }

        return result;
    }

    /**
     * V9: PBR 기반 적정가 계산 (Financial Services 전용) — V8 과 동일 로직, 버전 독립 원칙상 별도 유지
     * 공식: BPS × targetPBR × 추세팩터
     * - COE = riskFreeRate + Beta × marketRiskPremium
     * - targetPBR = ROE / COE (서브섹터별 상한 적용)
     * - 추세팩터: 연속하락 0.85, 단일하락 0.92, 그 외 1.0
     * - 순부채 차감 없음 (BPS가 이미 순자산 = 자산-부채 반영)
     * - ROE cap: 서브섹터별 (은행 18%, 보험 20%, 기타 25%)
     * - targetPBR 상한: 서브섹터별 (은행 1.8, 보험 2.0, 기타 2.5)
     */
    String calPerValueV9_Financial(CompanySharePriceCalculator req, CompanySharePriceResultDetail resultDetail,
                                          String sector, SectorCalculationParameters sectorParams) {

        resultDetail.setPBR기반평가(true);

        String industry = req.getIndustry();
        BigDecimal bps = new BigDecimal(req.getBookValuePerShare());
        BigDecimal roe = new BigDecimal(req.getRoe());

        // 서브섹터별 ROE cap & targetPBR 상한 결정
        BigDecimal roeCap = sectorParams.getRoeCap() != null ? sectorParams.getRoeCap() : new BigDecimal("0.20");
        BigDecimal subSectorMaxTargetPBR = sectorParams.getMaxTargetPBR();

        if (!StringUtil.isStringEmpty(industry)) {
            String ind = industry.toLowerCase();
            if (ind.contains("bank")) {
                // 은행: 레버리지가 높아 ROE가 부풀려짐, 보수적 평가
                roeCap = new BigDecimal("0.18");
                subSectorMaxTargetPBR = new BigDecimal("1.8");
            } else if (ind.contains("insurance")) {
                // 보험: 은행보다 레버리지 낮지만 여전히 보수적
                roeCap = new BigDecimal("0.20");
                subSectorMaxTargetPBR = new BigDecimal("2.0");
            } else {
                // Capital Markets, Credit Services, FinTech 등
                roeCap = new BigDecimal("0.25");
                subSectorMaxTargetPBR = new BigDecimal("2.5");
            }
        }

        // ROE cap 적용 (일시적 고ROE로 인한 과대평가 방지)
        BigDecimal cappedRoe = roe.min(roeCap);

        // Beta (기본값 1.0)
        BigDecimal beta = BigDecimal.ONE;
        if (!StringUtil.isStringEmpty(req.getBeta())) {
            try {
                beta = new BigDecimal(req.getBeta());
                if (beta.compareTo(BigDecimal.ZERO) <= 0) beta = BigDecimal.ONE;
            } catch (Exception e) {
                beta = BigDecimal.ONE;
            }
        }

        // COE = riskFreeRate + Beta × marketRiskPremium
        BigDecimal coe = sectorParams.getRiskFreeRate().add(
                beta.multiply(sectorParams.getMarketRiskPremium()));
        resultDetail.setCOE(coe.setScale(4, RoundingMode.HALF_UP).toPlainString());

        // targetPBR = cappedROE / COE (서브섹터별 범위 제한)
        BigDecimal targetPBR;
        if (coe.compareTo(BigDecimal.ZERO) <= 0) {
            targetPBR = sectorParams.getMinTargetPBR();
        } else {
            targetPBR = cappedRoe.divide(coe, 4, RoundingMode.HALF_UP);
        }
        targetPBR = targetPBR.max(sectorParams.getMinTargetPBR()).min(subSectorMaxTargetPBR);
        resultDetail.setTargetPBR(targetPBR.setScale(4, RoundingMode.HALF_UP).toPlainString());

        // 연간 추세팩터
        BigDecimal trendFactor = BigDecimal.ONE;
        BigDecimal curProfit = new BigDecimal(req.getOperatingProfitCurrent());
        BigDecimal preProfit = new BigDecimal(req.getOperatingProfitPre());
        BigDecimal prePreProfit = new BigDecimal(req.getOperatingProfitPrePre());

        if (curProfit.compareTo(preProfit) < 0 && preProfit.compareTo(prePreProfit) < 0) {
            trendFactor = new BigDecimal("0.85");
            resultDetail.set연속하락추세(true);
        } else if (curProfit.compareTo(preProfit) < 0) {
            trendFactor = new BigDecimal("0.92");
            resultDetail.set단일하락추세(true);
        } else if (curProfit.compareTo(preProfit) > 0 && preProfit.compareTo(prePreProfit) > 0) {
            resultDetail.set연속상승추세(true);
        }
        resultDetail.set영업이익추세팩터(trendFactor.toPlainString());

        // 적정가 = BPS × targetPBR × 추세팩터
        // ※ 순부채 차감 제거: BPS = (총자산-총부채)/주식수 이므로 이미 부채 반영됨 (이중 차감 방지)
        BigDecimal fairValue = bps.multiply(targetPBR).multiply(trendFactor)
                .setScale(2, RoundingMode.HALF_UP);

        // PBR 기반이므로 PER 관련 resultDetail도 세팅
        resultDetail.set실제PER(req.getPer() != null ? req.getPer() : "N/A");
        resultDetail.set섹터PER(sectorParams.getBasePER().toPlainString());
        resultDetail.set블렌딩PER("N/A (PBR경로)");
        resultDetail.set성장률보정PER("N/A (PBR경로)");
        resultDetail.setPEG(calcHelper.calPeg(req.getPer(), req.getEpsgrowth()));

        // 순부채 정보는 참고용으로만 기록 (적정가 차감하지 않음)
        String totalDebt = req.getTotalDebt();
        String cash = req.getCashAndCashEquivalents();
        if (!StringUtil.isStringEmpty(totalDebt) && !StringUtil.isStringEmpty(cash)) {
            BigDecimal netDebt = new BigDecimal(totalDebt).subtract(new BigDecimal(cash));
            resultDetail.set순부채(netDebt.toPlainString());
        }

        if(log.isDebugEnabled()) {
            log.debug("[V9 Financial PBR] 섹터:{}, 산업:{}, BPS:{}, ROE:{} (cap:{}→{}), Beta:{}, COE:{}, targetPBR:{} (max:{}), 추세:{}, 적정가:{}",
                    sector, industry, bps, roe, roeCap, cappedRoe, beta, coe, targetPBR, subSectorMaxTargetPBR, trendFactor, fairValue);
        }

        return fairValue.toPlainString();
    }

    /**
     * V9: 세후 이익 = max(0, 영업이익 − 이자비용) × (1 − 세율)
     * - 이자비용: max(실제 이자비용, 총부채 × 정상금리 6%) — 저금리·리스 부채 과소반영 방지
     * - 세율: 최근 연간 유효세율, 없거나 비정상(세전손실 등)이면 미국 법정세율 21%, 상한 35%
     */
    private String calAfterTaxEarningsV9(String operatingProfit, CompanySharePriceCalculator req,
                                         CompanySharePriceResultDetail resultDetail) {
        BigDecimal normalizedInterest = parseNonNegativeV9(req.getTotalDebt()).multiply(NORMALIZED_DEBT_RATE_V9);
        BigDecimal interest = parseNonNegativeV9(req.getInterestExpense()).max(normalizedInterest);
        BigDecimal taxRate = resolveTaxRateV9(req.getEffectiveTaxRate());
        BigDecimal pretax = new BigDecimal(operatingProfit).subtract(interest).max(BigDecimal.ZERO);
        resultDetail.set이자비용(interest.toPlainString());
        resultDetail.set적용세율(taxRate.toPlainString());
        return pretax.multiply(BigDecimal.ONE.subtract(taxRate)).toPlainString();
    }

    private BigDecimal resolveTaxRateV9(String effectiveTaxRate) {
        if (StringUtil.isStringEmpty(effectiveTaxRate)) return DEFAULT_TAX_RATE_V9;
        try {
            BigDecimal rate = new BigDecimal(effectiveTaxRate);
            if (rate.signum() < 0) return DEFAULT_TAX_RATE_V9;
            return rate.min(MAX_TAX_RATE_V9);
        } catch (NumberFormatException e) {
            return DEFAULT_TAX_RATE_V9;
        }
    }

    private BigDecimal parseNonNegativeV9(String value) {
        if (StringUtil.isStringEmpty(value)) return BigDecimal.ZERO;
        try {
            return new BigDecimal(value).max(BigDecimal.ZERO);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * V8: 기업가치 → 주당가치 (소수 2자리, 센트 단위)
     * - 기존 CalUtil.divide(3인자)는 정수 반올림이라 저가주 오차가 큼($1.49 → $1). PBR 경로와 동일하게 2자리로 통일
     * - 발행주식수 없음/0 이면 "0" (기존 CalUtil.divide 동작 유지)
     */
    String divideToPerShare(String enterpriseValue, String issuedShares) {
        if (StringUtil.isStringEmpty(issuedShares) || new BigDecimal(issuedShares.replace(",", "")).signum() == 0) {
            return "0";
        }
        String value = StringUtil.isStringEmpty(enterpriseValue) ? "0" : enterpriseValue;
        return CalUtil.divide(value, issuedShares, PER_SHARE_SCALE, RoundingMode.HALF_EVEN);
    }

    /**
     * V8: 고성장률 지속가능성 할인
     * 50% 초과분은 50%만 인정
     * 예: 성장률 80% → 50% + (30%×0.5) = 65%로 적용
     */
    BigDecimal applyGrowthDiscount(BigDecimal growth, BigDecimal sectorCap) {
        BigDecimal threshold = new BigDecimal("0.5");
        BigDecimal effectiveGrowth;

        if (growth.compareTo(threshold) > 0) {
            // 50% 초과분은 50%만 인정
            BigDecimal excess = growth.subtract(threshold);
            effectiveGrowth = threshold.add(excess.multiply(new BigDecimal("0.5")));
        } else {
            effectiveGrowth = growth;
        }

        // 섹터 캡 적용
        return effectiveGrowth.min(sectorCap);
    }

    /**
     * 한 주당 가치를 계산한다. (레거시 V1 - SecService/수동계산에서 사용)
     *
     * @param req 계산에 필요한 데이터가 담긴 StockValueManualReqDTO
     * @return 한 주 가격
     */
    public String calPerValue(CompanySharePriceCalculator req) {

        if(log.isDebugEnabled()) log.debug("CompanySharePriceCalculator = {}", req);

        // 1. 영업이익 평균 계산
        final String operatingProfitAvg = calcHelper.calOperatingProfitAvg(
                req.getOperatingProfitPrePre(),
                req.getOperatingProfitPre(),
                req.getOperatingProfitCurrent()
        );
        if(log.isDebugEnabled()) log.debug("영업이익 평균 = {}", operatingProfitAvg);

        // 요청 객체에서 필요한 값들을 final 변수로 할당
        final String assetsTotal = req.getCurrentAssetsTotal();             // 유동자산합계
        final String liabilitiesTotal = req.getCurrentLiabilitiesTotal();   // 유동부채합계
        final String currentRatio = req.getCurrentRatio();                  // 유동비율
        final String investmentAssets = req.getInvestmentAssets();          // 투자자산 (비유동자산 내)
        final String fixedLiabilities = req.getFixedLiabilities();          // 고정부채 (비유동부채)
        final String issuedShares = req.getIssuedShares();                  // 발행주식수

        // 2. 각 단계별 계산

        // 사업가치: 영업이익 평균 * 10(고정 PER)
        String per = StringUtil.isStringEmpty(req.getPer()) ? "10" : StringUtil.defaultString(req.getPer());
        requestContext.setAttribute(RequestContextConst.PER, per);
        final String businessValue = CalUtil.multi(operatingProfitAvg, per);
        if(log.isDebugEnabled()) log.debug("1. 사업가치 = {}", businessValue);
        requestContext.setAttribute(RequestContextConst.계산_사업가치, businessValue);

        // 재산가치: 유동자산 - (유동부채 * 유동비율) + 투자자산
        final String liabilityProduct = CalUtil.multi(liabilitiesTotal, currentRatio);
        final String assetDifference = CalUtil.sub(assetsTotal, liabilityProduct);
        final String assetValue = CalUtil.add(assetDifference, investmentAssets);
        if(log.isDebugEnabled()) log.debug("2. 재산가치 = {}", assetValue);
        requestContext.setAttribute(RequestContextConst.계산_재산가치, assetValue);

        // 부채: 고정부채 (비유동부채)
        final String debt = fixedLiabilities;
        if(log.isDebugEnabled()) log.debug("3. 부채 = {}", debt);
        requestContext.setAttribute(RequestContextConst.계산_부채, debt);

        // 기업가치: 사업가치 + 재산가치 - 부채
        final String companyValue = CalUtil.sub(CalUtil.add(businessValue, assetValue), debt);
        if(log.isDebugEnabled()) log.debug("4. 기업가치 = {}", companyValue);
        requestContext.setAttribute(RequestContextConst.계산_기업가치, companyValue);

        // 3. 한 주 가격 계산: (기업가치 * 단위복원) / 발행주식수
        return CalUtil.divide(
                CalUtil.multi(companyValue, req.getUnit()),
                issuedShares,
                RoundingMode.HALF_EVEN
        );
    }
}
