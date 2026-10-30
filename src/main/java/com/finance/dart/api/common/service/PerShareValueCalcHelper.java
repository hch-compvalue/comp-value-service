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
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * PerShareValueCalculationService 공유 헬퍼
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class PerShareValueCalcHelper {

    private final RequestContext requestContext;

    /**
     * 영업이익 가중평균을 계산한다. (V7: 1:2:3 가중치)
     *
     * @param profitPrePre 전전기 영업이익
     * @param profitPre    전기 영업이익
     * @param profitCurrent 당기 영업이익
     * @return 영업이익 가중평균 값
     */
    public String calOperatingProfitWeightedAvg(String profitPrePre, String profitPre, String profitCurrent) {
        String w1 = CalUtil.multi(profitPrePre, "1");
        String w2 = CalUtil.multi(profitPre, "2");
        String w3 = CalUtil.multi(profitCurrent, "3");
        String weightedSum = CalUtil.add(CalUtil.add(w1, w2), w3);
        return CalUtil.divide(weightedSum, "6", 2, RoundingMode.HALF_UP);
    }

    /**
     * 영업이익 평균을 계산한다.
     *
     * @param profitPrePre 전전기 영업이익
     * @param profitPre    전기 영업이익
     * @param profitCurrent 당기 영업이익
     * @return 영업이익 평균 값
     */
    public String calOperatingProfitAvg(String profitPrePre, String profitPre, String profitCurrent) {
        // 전전기 + 전기 + 당기를 한 번에 더한 후 평균 계산
        final String sum = CalUtil.add(CalUtil.add(profitPrePre, profitPre), profitCurrent);
        final String avg = CalUtil.divide(sum, "3", 2, RoundingMode.HALF_UP);

        requestContext.setAttribute(RequestContextConst.영업이익_합계, sum);
        requestContext.setAttribute(RequestContextConst.영업이익_평균, avg);

        return avg;
    }

    /**
     * 연구개발비 평균을 계산한다.
     */
    public String calRnDAvg(String rndPrePre, String rndPre, String rndCurrent) {
        // 전전기 + 전기 + 당기를 한 번에 더한 후 평균 계산
        final String sum = CalUtil.add(CalUtil.add(rndPrePre, rndPre), rndCurrent);
        final String avg = CalUtil.divide(sum, "3", 2, RoundingMode.HALF_UP);

        requestContext.setAttribute(RequestContextConst.연구개발비_합계, sum);
        requestContext.setAttribute(RequestContextConst.연구개발비_평균, avg);

        return avg;
    }

    /**
     * 유동비율(Current Ratio)에 따라 유동부채 차감 비율을 산출하는 메소드
     *
     * 기준:
     * - 유동비율 < 1.0    -> 1.0 (전액 차감, 단기 상환 위험 높음)
     * - 1.0 ~ 1.5 미만   -> 0.7
     * - 1.5 ~ 2.0 미만   -> 0.5
     * - 2.0 이상        -> 0.3 (유동성 충분)
     *
     * @param currentRatio 유동비율 (totalCurrentAssets / totalCurrentLiabilities)
     * @return 유동부채 차감 비율 (0.3 ~ 1.0)
     */
    public double getLiabilityFactor(Double currentRatio) {
        if (currentRatio == null) {
            // 데이터 없으면 보수적으로 전액 차감
            return 1.0;
        }

        if (currentRatio < 1.0) {
            return 1.0;
        } else if (currentRatio < 1.5) {
            return 0.7;
        } else if (currentRatio < 2.0) {
            return 0.5;
        } else {
            return 0.3;
        }
    }

    /**
     * <pre>
     * 성장률보정 PER 계산
     * 이론적 성장률 반영 + 현실적 밸류 안정화를 동시에 만족시키기 위한 보정 로직 추가
     * </pre>
     */
    public String calAdjustedPER(String incomGrowth, String per) {
        if (StringUtil.isStringEmpty(incomGrowth) || StringUtil.isStringEmpty(per)) return null;

        BigDecimal g = new BigDecimal(incomGrowth);

        // 역성장/정체(<=0)면 보정하지 않음: 기본 PER 반환
        if (g.signum() <= 0) return per;

        // 1) lambda-보정: 너무 작은 성장률 안정화
        BigDecimal lambda = new BigDecimal("0.05");     // 최소 기대 성장 5%
        BigDecimal gEff = g.add(lambda);                    // g + lambda

        // (선택) 상한 캡: 일시적 급등 완화
        BigDecimal gMax = new BigDecimal("0.40");       // 최대 40% 사용
        if (gEff.compareTo(gMax) > 0) gEff = gMax;

        // 2) 이론값: PER / (g + lambda)
        String perPureStr = CalUtil.divide(per, gEff.toPlainString(), 4, RoundingMode.HALF_UP);
        BigDecimal perPure = new BigDecimal(perPureStr);

        // 3) 시장 Anchor와 혼합
        BigDecimal anchor = new BigDecimal("25");       // 시장 평균 PER
        BigDecimal alpha  = new BigDecimal("0.4");      // 이론:시장 = 40:60
        BigDecimal adjusted = perPure.multiply(alpha)
                .add(anchor.multiply(BigDecimal.ONE.subtract(alpha)));

        // 4) 최종 캡 (과대 방지)
        BigDecimal cap = new BigDecimal("120");
        if (adjusted.compareTo(cap) > 0) adjusted = cap;

        return adjusted.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * <pre>
     * PEG 계산
     * PEG = PER / EPS성장률 -- 1 이하이면 성장 대비 저평가, 1 이상이면 고평가 가능
     * </pre>
     */
    public String calPeg(String per, String epsGrowth) {

        try {
            if (StringUtil.isStringEmpty(per) || StringUtil.isStringEmpty(epsGrowth)) return null;

            BigDecimal perBd = new BigDecimal(per);
            BigDecimal gBd   = new BigDecimal(epsGrowth); // abs() 쓰지 않음

            // 역성장/정체 -> PEG는 의미 없음
            if (gBd.signum() <= 0) return "999";

            // 분모는 "퍼센트". 0.x(비율)로 들어오면 x100, 이미 퍼센트(>1)이면 그대로
            BigDecimal growthPctBd = (gBd.compareTo(BigDecimal.ONE) > 0)
                    ? gBd
                    : gBd.multiply(new BigDecimal("100"));

            return perBd.divide(growthPctBd, 4, RoundingMode.HALF_UP).toPlainString();
        } catch (Exception ignore) {
            return null;
        }
    }

    // =====================================================================
    // V8 후처리 로직 (52주 캡, 안전마진, 매매가)
    // =====================================================================

    /**
     * V8: 52주 최고가 캡 + 급락 할인
     * - 계산값 > 52주 최고가: 가중평균 (계산값×0.4 + 최고가×0.6)
     * - 현재가가 52주 최고가 대비 30%+ 급락: 추가 20% 할인
     *
     * @param 계산된주당가치   PerShareValueCalculationService에서 산출한 적정가
     * @param historicalHigh52W 52주 최고가 (null이면 캡 미적용)
     * @param currentPrice     현재 주가
     * @param resultDetail     결과 상세 (급락종목할인 플래그 설정용)
     * @return 조정된 주당가치
     * @deprecated V8에서 더 이상 사용하지 않음. 내재가치는 순수 펀더멘털로만 산출
     */
    @Deprecated
    public String adjust52WeekHighCap(String 계산된주당가치, Double historicalHigh52W,
                                       String currentPrice, CompanySharePriceResultDetail resultDetail) {
        if (historicalHigh52W == null) {
            return 계산된주당가치;
        }

        BigDecimal 계산값 = new BigDecimal(계산된주당가치);
        BigDecimal 최고가 = BigDecimal.valueOf(historicalHigh52W);
        String 조정된주당가치;

        if (계산값.compareTo(최고가) > 0) {
            조정된주당가치 = 계산값.multiply(new BigDecimal("0.4"))
                    .add(최고가.multiply(new BigDecimal("0.6")))
                    .setScale(2, RoundingMode.HALF_UP)
                    .toPlainString();
        } else {
            조정된주당가치 = 계산된주당가치;
        }

        // 급락 할인 (30%+ 급락 시 20% 할인)
        BigDecimal currentPriceVal = new BigDecimal(StringUtil.defaultString(currentPrice, "0"));
        if (currentPriceVal.compareTo(BigDecimal.ZERO) > 0 && 최고가.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal dropRate = BigDecimal.ONE.subtract(currentPriceVal.divide(최고가, 4, RoundingMode.HALF_UP));
            if (dropRate.compareTo(new BigDecimal("0.3")) >= 0) {
                조정된주당가치 = new BigDecimal(조정된주당가치)
                        .multiply(new BigDecimal("0.8"))
                        .setScale(2, RoundingMode.HALF_UP)
                        .toPlainString();
                if (resultDetail != null) {
                    resultDetail.set급락종목할인(true);
                }
            }
        }

        return 조정된주당가치;
    }

    /**
     * V8: 동적 안전마진 계산
     * - 기본: 30%
     * - Beta 조정: +10%(>2.0), +7%(>1.5), +4%(>1.2), -5%(<0.5)
     * - 그레이엄 신뢰도: +5%(≤2개), +3%(≤3개)
     * - 범위: 25% ~ 45%
     *
     * @param beta            변동성 계수 (nullable)
     * @param grahamPassCount 그레이엄 스크리닝 통과 수 (0~5)
     * @return 안전마진율 (0.25 ~ 0.45)
     */
    public double calculateDynamicSafetyMargin(Double beta, int grahamPassCount) {
        double baseMargin = 0.30;

        double betaAdj = 0.0;
        if (beta != null) {
            if (beta > 2.0) betaAdj = 0.10;
            else if (beta > 1.5) betaAdj = 0.07;
            else if (beta > 1.2) betaAdj = 0.04;
            else if (beta < 0.5) betaAdj = -0.05;
        }

        double confidenceAdj = 0.0;
        if (grahamPassCount <= 2) confidenceAdj = 0.05;
        else if (grahamPassCount <= 3) confidenceAdj = 0.03;

        return Math.min(0.45, Math.max(0.25, baseMargin + betaAdj + confidenceAdj));
    }

    /**
     * V8: 매수적정가 계산 = 주당가치(내재가치) × (1 - 안전마진율)
     */
    public String calculatePurchasePrice(String 조정된주당가치, double safetyMargin) {
        String marginStr = String.format("%.2f", safetyMargin);
        return new BigDecimal(조정된주당가치)
                .multiply(BigDecimal.ONE.subtract(new BigDecimal(marginStr)))
                .setScale(2, RoundingMode.HALF_UP)
                .toPlainString();
    }

    /**
     * V8: 목표매도가 계산 = 주당가치(내재가치) × 0.95
     */
    public String calculateSellTarget(String 조정된주당가치) {
        return new BigDecimal(조정된주당가치)
                .multiply(new BigDecimal("0.95"))
                .setScale(2, RoundingMode.HALF_UP)
                .toPlainString();
    }

    /**
     * V8: 손절매가 계산 = 매수적정가 × 0.8
     */
    public String calculateStopLoss(String 매수적정가) {
        return new BigDecimal(매수적정가)
                .multiply(new BigDecimal("0.8"))
                .setScale(2, RoundingMode.HALF_UP)
                .toPlainString();
    }

    /**
     * 매출성장률에 따른 보정계수
     */
    public String getRevenueGrowthFactor(BigDecimal revenueGrowth) {
        // 20% 미만: PSR 적용 안함 (기존 자산가치만)
        // 20% ~ 30%: 0.3
        // 30% ~ 50%: 0.5
        // 50% ~ 80%: 0.7
        // 80% 이상: 0.9 (상한)

        if (revenueGrowth.compareTo(new BigDecimal("0.2")) < 0) {
            return null;  // PSR 미적용
        } else if (revenueGrowth.compareTo(new BigDecimal("0.3")) < 0) {
            return "0.3";
        } else if (revenueGrowth.compareTo(new BigDecimal("0.5")) < 0) {
            return "0.5";
        } else if (revenueGrowth.compareTo(new BigDecimal("0.8")) < 0) {
            return "0.7";
        } else {
            return "0.9";  // 상한
        }
    }

    /**
     * V9: 그레이엄 스크리닝 (PER/PBR/복합/유동비율/연속흑자 5개 기준 통과수 → resultDetail) — 미국·국내 공용
     * - 동적 안전마진이 통과수를 사용하므로 반드시 안전마진 계산 전에 호출
     */
    public void applyGrahamScreening(String symbol, SectorCalculationParameters sectorParams, CompanySharePriceCalculator calParam,
                                     CompanySharePriceResultDetail resultDetail, Double pbrTTM) {
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
            log.warn("[V9 그레이엄 스크리닝] {} - 스크리닝 실패: {}", symbol, e.getMessage());
        }
    }
}
