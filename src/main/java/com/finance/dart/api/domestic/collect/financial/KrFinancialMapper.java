package com.finance.dart.api.domestic.collect.financial;

import com.finance.dart.api.domestic.entity.KrFinancialReportEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * OpenDart fnlttSinglAcntAll 응답 행 → 재무 항목
 * - 표준 계정코드(account_id) 기준. 예외: 차입금은 표준코드 없는 행도 이름이 정확히 일치하면 더한다
 *   (표본 100사에서 '단기차입금'·'장기차입금'을 회사 고유 행으로 적은 경우가 많음 — 부분 일치는 소계 중복 위험이라 안 씀)
 * - 같은 항목 후보가 여러 개면 목록 순서대로 첫 값. 합계 계정이 있으면 합계, 없으면 구성 계정 합
 * - 손익: 손익계산서(IS) 우선, 없으면 포괄손익계산서(CIS — 단일 포괄손익계산서 회사·금융사)
 * - 분기 보고서: thstrm = 3개월, thstrm_add = 누적 / 사업보고서: thstrm = 연간 누적
 * - 현금흐름표 금액은 분기 보고서도 누적
 */
public final class KrFinancialMapper {

    static final String SJ_BS = "BS";
    static final String SJ_IS = "IS";
    static final String SJ_CIS = "CIS";
    static final String SJ_CF = "CF";
    static final String INTEREST_FROM_IS = "IS";
    static final String INTEREST_FROM_CF = "CF";
    private static final int RCEPT_DATE_LENGTH = 8;
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    /** 재무상태표 단일 계정 */
    static final List<String> TOTAL_ASSETS = List.of("ifrs-full_Assets");
    static final List<String> CURRENT_ASSETS = List.of("ifrs-full_CurrentAssets");
    static final List<String> CASH = List.of("ifrs-full_CashAndCashEquivalents", "dart_CashAndDuefromBanks");
    static final List<String> SHORT_TERM_DEPOSITS = List.of("ifrs-full_ShorttermDepositsNotClassifiedAsCashEquivalents",
            "dart_ShortTermDepositsNotClassifiedAsCashEquivalents");
    static final List<String> INVESTMENT_PROPERTY = List.of("ifrs-full_InvestmentProperty");
    static final List<String> CURRENT_LIABILITIES = List.of("ifrs-full_CurrentLiabilities");
    static final List<String> TOTAL_LIABILITIES = List.of("ifrs-full_Liabilities");
    static final List<String> TOTAL_EQUITY = List.of("ifrs-full_Equity");
    static final List<String> EQUITY_OWNERS = List.of("ifrs-full_EquityAttributableToOwnersOfParent");

    /** 재무상태표 합계 계정 → 없으면 구성 계정 합 */
    static final Composite INTANGIBLE = new Composite(List.of("ifrs-full_IntangibleAssetsAndGoodwill"),
            List.of("ifrs-full_IntangibleAssetsOtherThanGoodwill", "ifrs-full_Goodwill"));
    static final Composite EQUITY_METHOD = new Composite(
            List.of("ifrs-full_InvestmentAccountedForUsingEquityMethod", "ifrs-full_InvestmentsInSubsidiariesJointVenturesAndAssociates"),
            List.of("ifrs-full_InvestmentsInSubsidiaries", "ifrs-full_InvestmentsInAssociates", "ifrs-full_InvestmentsInJointVentures"));
    static final Composite NONCURRENT_FINANCIAL = new Composite(List.of("ifrs-full_NoncurrentFinancialAssets"),
            List.of("ifrs-full_NoncurrentFinancialAssetsAtFairValueThroughProfitOrLoss",
                    "ifrs-full_NoncurrentFinancialAssetsAtFairValueThroughProfitOrLossDesignatedUponInitialRecognition",
                    "ifrs-full_NoncurrentFinancialAssetsAtFairValueThroughProfitOrLossMandatorilyMeasuredAtFairValue",
                    "ifrs-full_NoncurrentInvestmentsInEquityInstrumentsDesignatedAtFairValueThroughOtherComprehensiveIncome",
                    "ifrs-full_NoncurrentFinancialAssetsAtFairValueThroughOtherComprehensiveIncome",
                    "ifrs-full_NoncurrentFinancialAssetsMeasuredAtFairValueThroughOtherComprehensiveIncome",
                    "ifrs-full_NoncurrentFinancialAssetsAtAmortisedCost",
                    "ifrs-full_OtherNoncurrentFinancialAssets",
                    "dart_LongTermDepositsNotClassifiedAsCashEquivalents"));
    static final Composite BORROWINGS = new Composite(List.of("ifrs-full_Borrowings"),
            List.of("ifrs-full_ShorttermBorrowings", "dart_CurrentLoansReceived", "ifrs-full_CurrentLoansReceived",
                    "ifrs-full_CurrentPortionOfLongtermBorrowings", "ifrs-full_CurrentPortionOfBondsIssued",
                    "ifrs-full_CurrentBondsIssued", "dart_CurrentPortionOfBonds",
                    "ifrs-full_NoncurrentPortionOfNoncurrentBondsIssued", "ifrs-full_NoncurrentPortionOfNoncurrentLoansReceived",
                    "ifrs-full_LongtermBorrowings", "dart_LongTermBorrowingsGross", "ifrs-full_NoncurrentBondsIssued",
                    "ifrs-full_BondsIssued", "ifrs-full_OtherBorrowings",
                    "dart_CurrentPortionOfConvertibleBonds", "dart_ConvertibleBondsNet",
                    "dart_CurrentPortionOfBondWithWarrant", "dart_BondWithWarrantNet"));
    /** "단기차입금 + 유동성장기차입금" 합쳐 적는 계정 — 있으면 아래 단기 개별 계정은 더하지 않는다 (중복 방지) */
    static final List<String> COMBINED_CURRENT_BORROWINGS = List.of(
            "ifrs-full_CurrentBorrowingsAndCurrentPortionOfNoncurrentBorrowings",
            "ifrs-full_CurrentLoansReceivedAndCurrentPortionOfNoncurrentLoansReceived",
            "ifrs-full_OtherCurrentBorrowingsAndCurrentPortionOfOtherNoncurrentBorrowings");
    static final Set<String> SEPARATE_CURRENT_BORROWINGS = Set.of("ifrs-full_ShorttermBorrowings", "dart_CurrentLoansReceived",
            "ifrs-full_CurrentLoansReceived", "ifrs-full_CurrentPortionOfLongtermBorrowings");
    /** 표준코드 없는 차입금 행 — 이름(공백 제거)이 정확히 일치할 때만 */
    static final Set<String> BORROWING_NAMES = Set.of("단기차입금", "장기차입금", "유동성장기차입금", "유동성장기부채",
            "사채", "유동성사채", "전환사채", "유동성전환사채", "신주인수권부사채", "유동성신주인수권부사채", "교환사채", "유동성교환사채");
    static final Composite LEASES = new Composite(List.of("ifrs-full_LeaseLiabilities"),
            List.of("ifrs-full_CurrentLeaseLiabilities", "ifrs-full_NoncurrentLeaseLiabilities"));

    /** 손익 */
    static final List<String> REVENUE = List.of("ifrs-full_Revenue");
    static final List<String> OPERATING_INCOME = List.of("dart_OperatingIncomeLoss", "ifrs-full_ProfitLossFromOperatingActivities");
    static final List<String> PRETAX_INCOME = List.of("ifrs-full_ProfitLossBeforeTax");
    static final List<String> INCOME_TAX = List.of("ifrs-full_IncomeTaxExpenseContinuingOperations", "ifrs-full_IncomeTaxExpense");
    static final List<String> NET_INCOME = List.of("ifrs-full_ProfitLoss");
    static final List<String> NET_INCOME_OWNERS = List.of("ifrs-full_ProfitLossAttributableToOwnersOfParent");
    static final List<String> INTEREST_EXPENSE_IS = List.of("ifrs-full_InterestExpense", "dart_InterestExpense");
    /** 현금흐름표: 이자비용 조정(발생주의, 손익의 이자비용과 같은 값) 우선, 없으면 이자 지급액 */
    static final List<String> INTEREST_CF = List.of("dart_AdjustmentsForInterestExpenses",
            "ifrs-full_InterestPaidClassifiedAsOperatingActivities", "ifrs-full_InterestPaidClassifiedAsFinancingActivities",
            "ifrs-full_InterestPaidClassifiedAsInvestingActivities", "ifrs-full_InterestPaid");

    /** 합계 계정 목록, 구성 계정 목록 */
    record Composite(List<String> totals, List<String> parts) {}

    private KrFinancialMapper() {}

    /**
     * @param rows 한 보고서의 응답 행 (연결 또는 별도 하나)
     */
    public static void apply(KrFinancialReportEntity e, List<Map<String, Object>> rows, KrReportType type) {
        Statement bs = Statement.of(rows, SJ_BS);
        Statement is = Statement.of(rows, SJ_IS).orElse(Statement.of(rows, SJ_CIS));
        Statement cf = Statement.of(rows, SJ_CF);

        Map<String, Object> first = rows.get(0);
        e.setRceptNo(text(first.get("rcept_no")));
        e.setRceptDate(rceptDate(e.getRceptNo()));
        e.setCurrency(text(first.get("currency")));

        e.setTotalAssets(bs.amount(TOTAL_ASSETS));
        e.setCurrentAssets(bs.amount(CURRENT_ASSETS));
        e.setCash(bs.amount(CASH));
        e.setShortTermDeposits(bs.amount(SHORT_TERM_DEPOSITS));
        e.setIntangibleAssets(bs.composite(INTANGIBLE));
        e.setEquityMethodInvestments(bs.composite(EQUITY_METHOD));
        e.setNoncurrentFinancialAssets(bs.composite(NONCURRENT_FINANCIAL));
        e.setInvestmentProperty(bs.amount(INVESTMENT_PROPERTY));
        e.setCurrentLiabilities(bs.amount(CURRENT_LIABILITIES));
        e.setTotalLiabilities(bs.amount(TOTAL_LIABILITIES));
        e.setBorrowings(borrowings(bs));
        e.setLeaseLiabilities(bs.composite(LEASES));
        e.setTotalEquity(bs.amount(TOTAL_EQUITY));
        e.setEquityOwners(bs.amount(EQUITY_OWNERS));

        e.setRevenueQ(is.quarter(REVENUE, type));
        e.setRevenueYtd(is.ytd(REVENUE, type));
        e.setOperatingIncomeQ(is.quarter(OPERATING_INCOME, type));
        e.setOperatingIncomeYtd(is.ytd(OPERATING_INCOME, type));
        e.setPretaxIncomeQ(is.quarter(PRETAX_INCOME, type));
        e.setPretaxIncomeYtd(is.ytd(PRETAX_INCOME, type));
        e.setIncomeTaxQ(is.quarter(INCOME_TAX, type));
        e.setIncomeTaxYtd(is.ytd(INCOME_TAX, type));
        e.setNetIncomeQ(is.quarter(NET_INCOME, type));
        e.setNetIncomeYtd(is.ytd(NET_INCOME, type));
        e.setNetIncomeOwnersQ(is.quarter(NET_INCOME_OWNERS, type));
        e.setNetIncomeOwnersYtd(is.ytd(NET_INCOME_OWNERS, type));

        Long interest = is.ytd(INTEREST_EXPENSE_IS, type);
        if (interest != null) {
            e.setInterestExpenseYtd(interest);
            e.setInterestSource(INTEREST_FROM_IS);
        } else {
            Long paid = cf.amount(INTEREST_CF);
            e.setInterestExpenseYtd(paid == null ? null : Math.abs(paid));     // 현금흐름표는 유출을 음수로 적는 회사가 있다
            e.setInterestSource(paid == null ? null : INTEREST_FROM_CF);
        }
    }

    /** 재무제표 1종 (account_id → 첫 행, 표준코드 없는 행은 이름 → 첫 행) */
    record Statement(Map<String, Map<String, Object>> byId, Map<String, Map<String, Object>> nonStandardByName) {

        static Statement of(List<Map<String, Object>> rows, String sjDiv) {
            Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
            Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                if (!sjDiv.equals(row.get("sj_div"))) continue;
                String id = text(row.get("account_id"));
                if (id != null && (id.startsWith("ifrs-full_") || id.startsWith("dart_"))) {
                    byId.putIfAbsent(id, row);
                } else {
                    String name = text(row.get("account_nm"));
                    if (name != null) byName.putIfAbsent(name.replaceAll("\\s", ""), row);
                }
            }
            return new Statement(byId, byName);
        }

        /** 표준코드 없는 행 중 이름이 정확히 일치하는 것의 합 */
        Long namedNonStandard(Set<String> names) {
            Long sum = null;
            for (String name : names) {
                Map<String, Object> row = nonStandardByName.get(name);
                Long v = row == null ? null : parseAmount(row.get("thstrm_amount"));
                if (v != null) sum = (sum == null ? 0 : sum) + v;
            }
            return sum;
        }

        Statement orElse(Statement other) {
            return byId.isEmpty() ? other : this;
        }

        /** 기말 잔액 / 사업보고서 금액 / 현금흐름표 누적 = thstrm */
        Long amount(List<String> ids) {
            return first(ids, "thstrm_amount");
        }

        Long composite(Composite c) {
            Long total = amount(c.totals());
            return total != null ? total : sumOf(c.parts());
        }

        /** 있는 계정만 합 (하나도 없으면 null) */
        Long sumOf(List<String> ids) {
            Long sum = null;
            for (String id : ids) {
                Long v = amount(List.of(id));
                if (v != null) sum = (sum == null ? 0 : sum) + v;
            }
            return sum;
        }

        Long quarter(List<String> ids, KrReportType type) {
            return type == KrReportType.ANNUAL ? null : first(ids, "thstrm_amount");
        }

        /** 사업보고서는 thstrm, 분기는 thstrm_add (1분기에 누적이 비어 있으면 3개월 = 누적) */
        Long ytd(List<String> ids, KrReportType type) {
            if (type == KrReportType.ANNUAL) return first(ids, "thstrm_amount");
            Long add = first(ids, "thstrm_add_amount");
            return add == null && type == KrReportType.Q1 ? first(ids, "thstrm_amount") : add;
        }

        private Long first(List<String> ids, String field) {
            for (String id : ids) {
                Map<String, Object> row = byId.get(id);
                if (row == null) continue;
                Long v = parseAmount(row.get(field));
                if (v != null) return v;
            }
            return null;
        }
    }

    /** 차입금 = 합계 계정, 없으면 (합쳐진 단기 계정 또는 단기 개별 계정) + 나머지 구성 계정 + 표준코드 없는 이름 일치 행 */
    static Long borrowings(Statement bs) {
        Long total = bs.amount(BORROWINGS.totals());
        if (total != null) return total;
        Long combined = bs.sumOf(COMBINED_CURRENT_BORROWINGS);
        List<String> parts = combined == null ? BORROWINGS.parts()
                : BORROWINGS.parts().stream().filter(id -> !SEPARATE_CURRENT_BORROWINGS.contains(id)).toList();
        return sum(sum(combined, bs.sumOf(parts)), bs.namedNonStandard(BORROWING_NAMES));
    }

    private static Long sum(Long a, Long b) {
        if (a == null) return b;
        return b == null ? a : a + b;
    }

    static Long parseAmount(Object value) {
        String text = text(value);
        if (text == null || text.equals("-")) return null;
        try {
            return new BigDecimal(text.replace(",", "")).longValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static LocalDate rceptDate(String rceptNo) {
        if (rceptNo == null || rceptNo.length() < RCEPT_DATE_LENGTH) return null;
        try {
            return LocalDate.parse(rceptNo.substring(0, RCEPT_DATE_LENGTH), YYYYMMDD);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String text(Object value) {
        if (value == null) return null;
        String s = value.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
