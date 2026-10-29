package com.finance.dart.api.domestic.collect.financial;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenDart 정기보고서 종류 + 결산기 계산
 * - bsns_year = 보고서 기간이 끝나는 해 (2026-10-29 3월 결산사 오리엔트바이오로 확인:
 *   2025 사업보고서 = 2025.03, 2026 1분기 = 2026.06, 2025 3분기 = 2025.12)
 * - 기간 종료월 = 결산월 + monthsAfterFiscalEnd (12월 결산: 1분기 3월, 반기 6월, 3분기 9월, 사업 12월)
 */
public enum KrReportType {

    Q1("11013", 3, 45),
    HALF("11012", 6, 45),
    Q3("11014", 9, 45),
    ANNUAL("11011", 0, 90),
    ;

    private static final int MONTHS_PER_YEAR = 12;
    /** 공시 목록 보고서명 예: "[기재정정]반기보고서 (2026.06)" */
    private static final Pattern PERIOD = Pattern.compile("\\((\\d{4})\\.(\\d{2})\\)");
    private static final String NAME_ANNUAL = "사업보고서";
    private static final String NAME_HALF = "반기보고서";
    private static final String NAME_QUARTER = "분기보고서";

    private final String code;
    private final int monthsAfterFiscalEnd;
    private final int filingDeadlineDays;   // 제출 기한 (기간 종료 후 일수 — 분기·반기 45일, 사업보고서 90일)

    KrReportType(String code, int monthsAfterFiscalEnd, int filingDeadlineDays) {
        this.code = code;
        this.monthsAfterFiscalEnd = monthsAfterFiscalEnd;
        this.filingDeadlineDays = filingDeadlineDays;
    }

    public String code() {
        return code;
    }

    public static Optional<KrReportType> fromCode(String code) {
        return Arrays.stream(values()).filter(t -> t.code.equals(code)).findFirst();
    }

    /** 제출 기한이 지났는가 (기한 전 공시는 공시 목록으로 받는다) */
    public boolean deadlinePassed(LocalDate periodEnd, LocalDate today) {
        return today.isAfter(periodEnd.plusDays(filingDeadlineDays));
    }

    /** 결산기 종료일 (그 달 말일) */
    public LocalDate periodEnd(int bsnsYear, int fiscalMonth) {
        int month = (fiscalMonth - 1 + monthsAfterFiscalEnd) % MONTHS_PER_YEAR + 1;
        return YearMonth.of(bsnsYear, month).atEndOfMonth();
    }

    /** 같은 회계연도 3분기 보고서의 기간 종료일 (사업보고서 기간 종료 3개월 전) — 4분기 = 연간 − 3분기 누적 */
    public static LocalDate q3PeriodEndOf(LocalDate annualPeriodEnd) {
        return YearMonth.from(annualPeriodEnd).minusMonths(MONTHS_PER_YEAR - Q3.monthsAfterFiscalEnd).atEndOfMonth();
    }

    /** 4분기 단독 값 (둘 중 하나라도 없으면 null) */
    public static Long q4Standalone(Long annualYtd, Long q3Ytd) {
        return annualYtd == null || q3Ytd == null ? null : annualYtd - q3Ytd;
    }

    /** 공시 목록 보고서명 → (사업연도, 종류). 결산월과 기간이 맞지 않으면 빈 값 */
    public static Optional<Target> fromReportName(String reportName, int fiscalMonth) {
        if (reportName == null) return Optional.empty();
        Matcher m = PERIOD.matcher(reportName);
        if (!m.find()) return Optional.empty();
        int year = Integer.parseInt(m.group(1));
        int month = Integer.parseInt(m.group(2));
        int offset = Math.floorMod(month - fiscalMonth, MONTHS_PER_YEAR);

        KrReportType type;
        if (reportName.contains(NAME_ANNUAL)) type = ANNUAL;
        else if (reportName.contains(NAME_HALF)) type = HALF;
        else if (reportName.contains(NAME_QUARTER)) type = offset == Q1.monthsAfterFiscalEnd ? Q1 : Q3;
        else return Optional.empty();
        return type.monthsAfterFiscalEnd == offset ? Optional.of(new Target(year, type)) : Optional.empty();
    }

    /** 수집 대상 보고서 */
    public record Target(int bsnsYear, KrReportType type) {}
}
