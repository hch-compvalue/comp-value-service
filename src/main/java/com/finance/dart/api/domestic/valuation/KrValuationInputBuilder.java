package com.finance.dart.api.domestic.valuation;

import com.finance.dart.api.common.dto.CompanySharePriceCalculator;
import com.finance.dart.api.common.dto.CompanySharePriceResultDetail;
import com.finance.dart.api.domestic.entity.KrFinancialReportEntity;
import com.finance.dart.api.domestic.entity.KrStockEntity;
import com.finance.dart.api.domestic.enums.KrExchange;
import com.finance.dart.api.domestic.repository.KrFinancialReportRepository;
import com.finance.dart.api.domestic.repository.KrPriceJdbcRepository;
import com.finance.dart.api.domestic.repository.KrStockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 국내 V9 계산 입력 조립 — 미국 getCalParamDataV9 와 같은 정의를 우리 DB(재무·일봉·종목)로 만든다
 * - 연간 영업이익 3년, 매출(최근 연간)·매출성장률, PSR(시총 ÷ 최근 4분기 매출)
 * - 최근 재무상태표: 유동자산·유동부채·유동비율, 투자자산(관계기업 + 장기금융자산), 무형자산, 차입금+리스(총부채), 현금
 * - PER: 최근 5년 연간 PER(결산기 말 종가 × 주식수 ÷ 지배주주 순이익) 중 양수 평균, 없으면 최근 4분기(TTM) PER
 * - 이자비용·유효세율: 최근 사업보고서 / 분기 영업이익 4개(최신순) / 베타: 월간 수익률 vs KOSPI
 * - 계산하면 틀리는 종목은 제외하고 사유를 남긴다 (원화 외 공시, 재무상태표 합계 불일치, 사업보고서 3년 미만 등)
 * - 금액이 1,000배로 들어온 보고서(천 원 단위 공시)는 스냅샷에서 빼고 경고
 */
@Component
@RequiredArgsConstructor
public class KrValuationInputBuilder {

    static final int REQUIRED_ANNUALS = 3;
    static final int PER_YEARS = 5;
    static final int PRICE_HISTORY_YEARS = 6;
    static final int SMA_SHORT = 50;
    static final int SMA_LONG = 200;
    /** 자산 = 부채 + 자본 허용 오차 (보험사 특별계정 등 회사 고유 계정이 섞인 공시 제외) */
    static final double BALANCE_TOLERANCE = 0.01;
    private static final String KRW = "KRW";
    private static final String ACTIVE = "Y";
    private static final int RATIO_SCALE = 4;
    private static final BigDecimal CURRENT_RATIO_NO_ASSETS_LIABILITIES = new BigDecimal("1.0");
    private static final BigDecimal CURRENT_RATIO_NO_LIABILITIES = new BigDecimal("2.0");

    private final KrStockRepository stockRepository;
    private final KrFinancialReportRepository reportRepository;
    private final KrPriceJdbcRepository priceRepository;

    /** 조립 결과 — excluded 가 있으면 계산하지 않는다 */
    public record Input(KrStockEntity stock, CompanySharePriceCalculator calParam, CompanySharePriceResultDetail detail,
                        KrSectorMapper.KrSector sector, Double pbr, Double beta, double price, LocalDate priceDate,
                        String excluded, List<String> warnings) {}

    public Input build(String stockCode, LocalDate asOf) {
        CompanySharePriceResultDetail detail = new CompanySharePriceResultDetail(KrValuationService.UNIT);
        List<String> warnings = new ArrayList<>();
        KrStockEntity stock = stockRepository.findById(stockCode).orElse(null);
        if (stock == null) return excluded(null, detail, "종목 없음");
        if (!ACTIVE.equals(stock.getActive())) return excluded(stock, detail, "상장폐지(종목 목록에 없음)");
        Long shares = stock.getListedShares();
        if (shares == null || shares <= 0) return excluded(stock, detail, "상장주식수 없음");

        KrPriceSeries prices = KrPriceSeries.of(priceRepository.findDailyCloses(stockCode, asOf.minusYears(PRICE_HISTORY_YEARS)))
                .upTo(asOf);
        if (prices.isEmpty()) return excluded(stock, detail, "일봉 없음");
        double price = prices.last().close();
        BigDecimal marketCap = BigDecimal.valueOf(price).multiply(BigDecimal.valueOf(shares));

        KrFinancialSnapshot snap = KrFinancialSnapshot.build(reportRepository.findByStockCode(stockCode), asOf);
        KrFinancialReportEntity latest = snap.latest();
        if (snap.unitAnomalies() > 0) warnings.add("공시 단위 이상 보고서 " + snap.unitAnomalies() + "건 제외(천 원 단위 의심)");
        List<KrFinancialReportEntity> annuals = snap.annuals().stream().filter(a -> a.getOperatingIncomeYtd() != null).toList();
        if (latest == null) return excluded(stock, detail, "재무 없음");
        if (annuals.size() < REQUIRED_ANNUALS) return excluded(stock, detail, "사업보고서 " + REQUIRED_ANNUALS + "년치 부족(" + annuals.size() + ")");
        String currencyIssue = nonKrw(latest, annuals.subList(0, REQUIRED_ANNUALS));
        if (currencyIssue != null) return excluded(stock, detail, "원화 외 통화 공시(" + currencyIssue + ")");
        if (latest.getTotalAssets() == null || latest.getTotalEquity() == null) return excluded(stock, detail, "재무상태표 자산·자본 없음");
        if (!balanced(latest)) return excluded(stock, detail, "재무상태표 합계 불일치(자산 ≠ 부채 + 자본)");

        KrSectorMapper.KrSector sector = KrSectorMapper.map(stockCode, stock.getKsicCode());
        if (sector.sector() == null) warnings.add("업종 매핑 안 됨(KSIC " + stock.getKsicCode() + ") — 기본 파라미터");

        CompanySharePriceCalculator c = new CompanySharePriceCalculator();
        c.setUnit(KrValuationService.UNIT);
        KrFinancialReportEntity a0 = annuals.get(0), a1 = annuals.get(1), a2 = annuals.get(2);

        //@ 영업이익 3년 (당기·전기·전전기)
        c.setOperatingProfitCurrent(str(a0.getOperatingIncomeYtd()));
        c.setOperatingProfitPre(str(a1.getOperatingIncomeYtd()));
        c.setOperatingProfitPrePre(str(a2.getOperatingIncomeYtd()));
        detail.set영업이익_당기(c.getOperatingProfitCurrent());
        detail.set영업이익_전기(c.getOperatingProfitPre());
        detail.set영업이익_전전기(c.getOperatingProfitPrePre());

        //@ 분기 영업이익 4개 (최신순, 연속 4개가 없으면 분기 추세 미적용)
        List<KrFinancialSnapshot.Quarter> quarters = snap.lastFourQuarters();
        if (quarters.size() == KrFinancialSnapshot.QUARTERS_PER_YEAR && quarters.stream().allMatch(q -> q.operatingIncome() != null)) {
            c.setQuarterlyOpIncomeQ1(str(quarters.get(0).operatingIncome()));
            c.setQuarterlyOpIncomeQ2(str(quarters.get(1).operatingIncome()));
            c.setQuarterlyOpIncomeQ3(str(quarters.get(2).operatingIncome()));
            c.setQuarterlyOpIncomeQ4(str(quarters.get(3).operatingIncome()));
        }

        //@ 매출·성장률·PSR
        c.setRevenue(str(a0.getRevenueYtd()));
        c.setRevenueGrowth(growth(a0.getRevenueYtd(), a1.getRevenueYtd()));
        Long ttmRevenue = snap.ttmRevenue() != null ? snap.ttmRevenue() : a0.getRevenueYtd();
        c.setPsr(ttmRevenue != null && ttmRevenue > 0 ? ratio(marketCap, BigDecimal.valueOf(ttmRevenue)) : "0");

        //@ 재무상태표 (최근 보고서)
        String currentAssets = str(latest.getCurrentAssets());
        String currentLiabilities = str(latest.getCurrentLiabilities());
        c.setCurrentAssetsTotal(currentAssets);
        c.setCurrentLiabilitiesTotal(currentLiabilities);
        c.setCurrentRatio(currentRatio(latest.getCurrentAssets(), latest.getCurrentLiabilities()));
        c.setInvestmentAssets(str(sum(latest.getEquityMethodInvestments(), latest.getNoncurrentFinancialAssets())));
        c.setIntangibleAssets(str(latest.getIntangibleAssets()));
        c.setIssuedShares(String.valueOf(shares));
        c.setTotalDebt(str(sum(latest.getBorrowings(), latest.getLeaseLiabilities())));
        Long cash = latest.getCash();
        if (cash == null || cash == 0) cash = sum(latest.getCash(), latest.getShortTermDeposits());    // 미국과 같은 폴백
        c.setCashAndCashEquivalents(str(cash));
        detail.set유동자산합계(currentAssets);
        detail.set유동부채합계(currentLiabilities);
        detail.set유동비율(c.getCurrentRatio());
        detail.set투자자산_비유동자산내(c.getInvestmentAssets());
        detail.set무형자산(c.getIntangibleAssets());
        detail.set발행주식수(c.getIssuedShares());
        detail.set총부채(c.getTotalDebt());
        detail.set현금성자산(c.getCashAndCashEquivalents());
        c.setRndCurrent("0");
        c.setRndPre("0");
        c.setRndPrePre("0");

        //@ PER (5년 연간 평균 → 없으면 TTM), PBR
        String per = averageAnnualPer(snap.annuals(), prices, shares);
        if (per == null) {
            Long ttmNi = snap.ttmNetIncome() != null ? snap.ttmNetIncome() : KrFinancialSnapshot.annualNetIncome(a0);
            per = ttmNi == null || ttmNi == 0 ? "0" : ratio(marketCap, BigDecimal.valueOf(ttmNi));
            warnings.add("연간 PER 없음 — TTM PER 사용");
        }
        c.setPer(per);
        detail.setPER(per);
        Long equity = latest.getEquityOwners() != null ? latest.getEquityOwners() : latest.getTotalEquity();
        Double pbr = equity != null && equity > 0 ? marketCap.divide(BigDecimal.valueOf(equity), RATIO_SCALE, RoundingMode.HALF_UP).doubleValue() : null;
        if (pbr != null) detail.setPBR(String.valueOf(pbr));

        //@ 성장률
        c.setEpsgrowth(growth(KrFinancialSnapshot.annualNetIncome(a0), KrFinancialSnapshot.annualNetIncome(a1)));
        c.setOperatingIncomeGrowth(growth(a0.getOperatingIncomeYtd(), a1.getOperatingIncomeYtd()));
        detail.setEPS성장률(c.getEpsgrowth());
        detail.set영업이익성장률(c.getOperatingIncomeGrowth());

        //@ 금융(PBR 경로): BPS·ROE(최근 4분기 순이익 ÷ 자본)
        if (KrSectorMapper.FINANCIAL.equals(sector.sector()) && equity != null && equity > 0) {
            BigDecimal bps = BigDecimal.valueOf(equity).divide(BigDecimal.valueOf(shares), 2, RoundingMode.HALF_UP);
            c.setBookValuePerShare(bps.toPlainString());
            detail.setBPS(bps.toPlainString());
            Long ttmNi = snap.ttmNetIncome();
            if (ttmNi != null) {
                String roe = ratio(BigDecimal.valueOf(ttmNi), BigDecimal.valueOf(equity));
                c.setRoe(roe);
                detail.setROE(roe);
            }
        }
        c.setIndustry(sector.industry());

        //@ 이자비용·유효세율 (최근 사업보고서)
        c.setInterestExpense(str(a0.getInterestExpenseYtd()));
        if (a0.getPretaxIncomeYtd() != null && a0.getPretaxIncomeYtd() > 0 && a0.getIncomeTaxYtd() != null) {
            c.setEffectiveTaxRate(ratio(BigDecimal.valueOf(a0.getIncomeTaxYtd()), BigDecimal.valueOf(a0.getPretaxIncomeYtd())));
        }

        //@ 현재가·이동평균·베타
        c.setPrice(price);
        Double sma50 = prices.sma(SMA_SHORT), sma200 = prices.sma(SMA_LONG);
        if (sma50 != null) detail.setSMA50(String.valueOf(sma50));
        if (sma200 != null) detail.setSMA200(String.valueOf(sma200));
        KrPriceSeries market = KrPriceSeries.of(priceRepository.findIndexCloses(KrExchange.KOSPI.getIndexCode(),
                asOf.minusYears(PRICE_HISTORY_YEARS))).upTo(asOf);
        Double beta = KrPriceSeries.beta(prices, market);
        if (beta != null) c.setBeta(String.valueOf(beta));
        else warnings.add("베타 계산 불가(월간 수익률 " + KrPriceSeries.BETA_MIN_MONTHS + "개월 미만)");

        return new Input(stock, c, detail, sector, pbr, beta, price, prices.last().date(), null, warnings);
    }

    /** 최근 5년 연간 PER 중 양수 평균 (결산기 말 종가 × 현재 주식수 ÷ 그해 순이익). 하나도 없으면 null */
    static String averageAnnualPer(List<KrFinancialReportEntity> annuals, KrPriceSeries prices, long shares) {
        List<Double> pers = new ArrayList<>();
        for (KrFinancialReportEntity a : annuals.subList(0, Math.min(PER_YEARS, annuals.size()))) {
            Long ni = KrFinancialSnapshot.annualNetIncome(a);
            Double close = prices.closeOnOrBefore(a.getPeriodEnd());
            if (ni == null || ni <= 0 || close == null) continue;
            pers.add(close * shares / ni);
        }
        return pers.isEmpty() ? null : String.valueOf(pers.stream().mapToDouble(Double::doubleValue).average().orElse(0));
    }

    /** (당기 − 전기) ÷ |전기| — 전기가 없거나 0이면 0 */
    static String growth(Long current, Long previous) {
        if (current == null || previous == null || previous == 0) return "0";
        return BigDecimal.valueOf(current - previous).divide(BigDecimal.valueOf(Math.abs(previous)), RATIO_SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    /** 미국 getCalParamDataV9 와 같은 규칙 */
    static String currentRatio(Long assets, Long liabilities) {
        long a = assets == null ? 0 : assets, l = liabilities == null ? 0 : liabilities;
        if (a == 0 && l == 0) return CURRENT_RATIO_NO_ASSETS_LIABILITIES.toPlainString();
        if (l == 0) return CURRENT_RATIO_NO_LIABILITIES.toPlainString();
        if (a == 0) return "0.0";
        return BigDecimal.valueOf(a).divide(BigDecimal.valueOf(l), 2, RoundingMode.HALF_UP).toPlainString();
    }

    static boolean balanced(KrFinancialReportEntity r) {
        if (r.getTotalLiabilities() == null) return false;
        double gap = Math.abs(r.getTotalAssets() - (r.getTotalLiabilities() + r.getTotalEquity()));
        return gap <= Math.abs(r.getTotalAssets()) * BALANCE_TOLERANCE;
    }

    private static String nonKrw(KrFinancialReportEntity latest, List<KrFinancialReportEntity> annuals) {
        if (latest.getCurrency() != null && !KRW.equals(latest.getCurrency())) return latest.getCurrency();
        return annuals.stream().map(KrFinancialReportEntity::getCurrency).filter(cur -> cur != null && !KRW.equals(cur)).findFirst().orElse(null);
    }

    private static Input excluded(KrStockEntity stock, CompanySharePriceResultDetail detail, String reason) {
        return new Input(stock, null, detail, null, null, null, 0, null, reason, List.of());
    }

    private static String ratio(BigDecimal numerator, BigDecimal denominator) {
        return numerator.divide(denominator, RATIO_SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    private static Long sum(Long a, Long b) {
        if (a == null) return b == null ? null : b;
        return b == null ? a : a + b;
    }

    private static String str(Long v) {
        return v == null ? "0" : String.valueOf(v);
    }
}
