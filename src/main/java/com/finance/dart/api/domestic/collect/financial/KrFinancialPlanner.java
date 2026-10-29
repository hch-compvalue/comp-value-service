package com.finance.dart.api.domestic.collect.financial;

import com.finance.dart.api.domestic.collect.financial.KrFinancialCollector.Company;
import com.finance.dart.api.domestic.repository.KrFinancialReportRepository.ReportState;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 이번 실행에서 받을 재무 보고서 목록
 * 1) 공시 목록: 최근 정기보고서를 낸 회사 (신규·정정) — 그 공시일 이후 확인한 적 없으면 다시 받는다
 * 2) 이력 채우기: 5년 안의 보고서 중 아직 없는 것, 최신 기간부터
 *    - NONE(데이터 없음)은 기간 종료 150일 이내(공시 기한 안)일 때만 7일마다 다시 확인
 */
public final class KrFinancialPlanner {

    static final int HISTORY_YEARS = 5;
    static final int NONE_RECHECK_WITHIN_DAYS = 150;
    static final int NONE_RECHECK_INTERVAL_DAYS = 7;

    /** 받을 보고서 */
    public record Task(Company company, KrReportType.Target target, LocalDate periodEnd) {}

    /** 공시 목록 1건 */
    public record Disclosure(String stockCode, String reportName, LocalDate rceptDate) {}

    private KrFinancialPlanner() {}

    public static String key(String stockCode, int bsnsYear, String reprtCode) {
        return stockCode + "|" + bsnsYear + "|" + reprtCode;
    }

    public static Map<String, ReportState> index(List<ReportState> states) {
        Map<String, ReportState> map = new HashMap<>();
        for (ReportState s : states) map.put(key(s.getStockCode(), s.getBsnsYear(), s.getReprtCode()), s);
        return map;
    }

    /** 종목별 최근 데이터가 있던 연결/별도 구분 */
    public static Map<String, String> preferredFs(List<ReportState> states) {
        Map<String, ReportState> latest = new HashMap<>();
        for (ReportState s : states) {
            if (KrFinancialCollector.FS_NONE.equals(s.getFsDiv())) continue;
            latest.merge(s.getStockCode(), s, (a, b) -> a.getPeriodEnd().isAfter(b.getPeriodEnd()) ? a : b);
        }
        Map<String, String> result = new HashMap<>();
        latest.forEach((code, s) -> result.put(code, s.getFsDiv()));
        return result;
    }

    public static List<Task> fromDisclosures(List<Disclosure> disclosures, Map<String, Company> companies,
                                             Map<String, ReportState> states) {
        Map<String, Task> tasks = new LinkedHashMap<>();
        for (Disclosure d : disclosures) {
            Company company = companies.get(d.stockCode());
            if (company == null) continue;
            Optional<KrReportType.Target> target = KrReportType.fromReportName(d.reportName(), company.fiscalMonth());
            if (target.isEmpty()) continue;
            String key = key(company.stockCode(), target.get().bsnsYear(), target.get().type().code());
            ReportState state = states.get(key);
            boolean checkedAfterFiling = state != null && state.getCheckedAt().toLocalDate().isAfter(d.rceptDate());
            if (checkedAfterFiling) continue;
            tasks.putIfAbsent(key, new Task(company, target.get(),
                    target.get().type().periodEnd(target.get().bsnsYear(), company.fiscalMonth())));
        }
        return new ArrayList<>(tasks.values());
    }

    public static List<Task> backfill(Collection<Company> companies, Map<String, ReportState> states,
                                      Set<String> alreadyPlanned, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        int firstYear = today.getYear() - HISTORY_YEARS;
        List<Task> tasks = new ArrayList<>();
        for (Company company : companies) {
            for (int year = firstYear; year <= today.getYear(); year++) {
                for (KrReportType type : KrReportType.values()) {
                    LocalDate periodEnd = type.periodEnd(year, company.fiscalMonth());
                    if (periodEnd.isAfter(today)) continue;
                    String key = key(company.stockCode(), year, type.code());
                    if (alreadyPlanned.contains(key) || !needsFetch(states.get(key), periodEnd, now)) continue;
                    tasks.add(new Task(company, new KrReportType.Target(year, type), periodEnd));
                }
            }
        }
        tasks.sort(Comparator.comparing(Task::periodEnd).reversed());   // 최신 기간부터 (3단계 계산에 먼저 필요)
        return tasks;
    }

    static boolean needsFetch(ReportState state, LocalDate periodEnd, LocalDateTime now) {
        if (state == null) return true;
        if (!KrFinancialCollector.FS_NONE.equals(state.getFsDiv())) return false;
        boolean withinDeadline = !periodEnd.isBefore(now.toLocalDate().minusDays(NONE_RECHECK_WITHIN_DAYS));
        boolean stale = state.getCheckedAt().isBefore(now.minusDays(NONE_RECHECK_INTERVAL_DAYS));
        return withinDeadline && stale;
    }
}
