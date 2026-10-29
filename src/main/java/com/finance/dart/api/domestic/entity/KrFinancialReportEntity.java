package com.finance.dart.api.domestic.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 국내 재무 — OpenDart 정기보고서 1건 = 1행 (fnlttSinglAcntAll, 원 단위)
 * - bsns_year = 보고서 기간이 끝나는 해 (3월 결산사는 같은 해에 사업보고서(3월)와 1분기(6월)가 함께 있다)
 * - 분기 짝(4분기 = 연간 − 3분기 누적)은 bsns_year가 아니라 period_end로 맞춘다 (KrFinancialPeriods)
 * - 손익은 3개월 값(_q)과 연초부터 누적(_ytd)을 보고서에 적힌 그대로 저장. 사업보고서는 _q 없음
 * - 백테스트는 rcept_date 이후에만 사용 (정정공시로 다시 받으면 rcept_no·rcept_date가 바뀐다 = 보수적)
 * - fs_div NONE = 연결·별도 모두 데이터 없음 (아직 미공시 등) — checked_at으로 재확인 주기 판단
 */
@Data
@NoArgsConstructor
@Entity
@IdClass(KrFinancialReportEntity.Key.class)
@Table(name = "tb_kr_financial_report", schema = "public",
        indexes = @Index(name = "idx_kr_financial_report_period", columnList = "stock_code, period_end"))
public class KrFinancialReportEntity {

    @Id
    @Column(name = "stock_code", length = 6)
    private String stockCode;

    @Id
    @Column(name = "bsns_year")
    private Integer bsnsYear;

    @Id
    @Column(name = "reprt_code", length = 5)
    private String reprtCode;               // 11013 1분기 / 11012 반기 / 11014 3분기 / 11011 사업보고서

    @Column(name = "corp_code", nullable = false, length = 8)
    private String corpCode;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;            // 결산기 종료일 (결산월 기준 계산)

    @Column(name = "fs_div", nullable = false, length = 4)
    private String fsDiv;                   // CFS 연결 / OFS 별도 / NONE 데이터 없음

    @Column(name = "rcept_no", length = 14)
    private String rceptNo;

    @Column(name = "rcept_date")
    private LocalDate rceptDate;            // 접수일 (rcept_no 앞 8자리)

    @Column(name = "currency", length = 3)
    private String currency;                // 대부분 KRW — 아니면 3단계에서 환산/제외

    @Column(name = "checked_at", nullable = false)
    private LocalDateTime checkedAt;

    /** 재무상태표 (기말 잔액) **/
    @Column(name = "total_assets") private Long totalAssets;
    @Column(name = "current_assets") private Long currentAssets;
    @Column(name = "cash") private Long cash;                                       // 현금및현금성자산 (은행: 현금 및 예치금)
    @Column(name = "short_term_deposits") private Long shortTermDeposits;           // 단기금융상품
    @Column(name = "intangible_assets") private Long intangibleAssets;              // 무형자산(영업권 포함)
    @Column(name = "equity_method_investments") private Long equityMethodInvestments; // 관계기업·공동기업 투자
    @Column(name = "noncurrent_financial_assets") private Long noncurrentFinancialAssets; // 장기 금융자산 합 (FVPL·FVOCI·상각후원가·장기금융상품)
    @Column(name = "investment_property") private Long investmentProperty;
    @Column(name = "current_liabilities") private Long currentLiabilities;
    @Column(name = "total_liabilities") private Long totalLiabilities;
    @Column(name = "borrowings") private Long borrowings;                           // 차입금·사채 (리스 제외)
    @Column(name = "lease_liabilities") private Long leaseLiabilities;
    @Column(name = "total_equity") private Long totalEquity;
    @Column(name = "equity_owners") private Long equityOwners;                      // 지배기업 소유주지분

    /** 손익 (3개월 / 연초부터 누적) **/
    @Column(name = "revenue_q") private Long revenueQ;
    @Column(name = "revenue_ytd") private Long revenueYtd;
    @Column(name = "operating_income_q") private Long operatingIncomeQ;
    @Column(name = "operating_income_ytd") private Long operatingIncomeYtd;
    @Column(name = "pretax_income_q") private Long pretaxIncomeQ;
    @Column(name = "pretax_income_ytd") private Long pretaxIncomeYtd;
    @Column(name = "income_tax_q") private Long incomeTaxQ;
    @Column(name = "income_tax_ytd") private Long incomeTaxYtd;
    @Column(name = "net_income_q") private Long netIncomeQ;
    @Column(name = "net_income_ytd") private Long netIncomeYtd;
    @Column(name = "net_income_owners_q") private Long netIncomeOwnersQ;
    @Column(name = "net_income_owners_ytd") private Long netIncomeOwnersYtd;

    /** 이자비용 (누적) — 손익계산서 이자비용 우선, 없으면 현금흐름표 이자의 지급. 금융비용은 환손실이 섞여 사용 안 함 **/
    @Column(name = "interest_expense_ytd") private Long interestExpenseYtd;
    @Column(name = "interest_source", length = 2) private String interestSource;    // IS / CF

    @Data
    @NoArgsConstructor
    public static class Key implements Serializable {
        private String stockCode;
        private Integer bsnsYear;
        private String reprtCode;
    }
}
