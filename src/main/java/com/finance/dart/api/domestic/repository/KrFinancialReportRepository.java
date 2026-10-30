package com.finance.dart.api.domestic.repository;

import com.finance.dart.api.domestic.entity.KrFinancialReportEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface KrFinancialReportRepository extends JpaRepository<KrFinancialReportEntity, KrFinancialReportEntity.Key> {

    /** 수집 상태만 (재무 값 제외) — 배치가 무엇을 받아야 하는지 판단 */
    interface ReportState {
        String getStockCode();
        Integer getBsnsYear();
        String getReprtCode();
        String getFsDiv();
        LocalDate getPeriodEnd();
        LocalDateTime getCheckedAt();
    }

    List<KrFinancialReportEntity> findByStockCode(String stockCode);

    @Query("select r.stockCode as stockCode, r.bsnsYear as bsnsYear, r.reprtCode as reprtCode, r.fsDiv as fsDiv, "
            + "r.periodEnd as periodEnd, r.checkedAt as checkedAt from KrFinancialReportEntity r")
    List<ReportState> findAllStates();
}
