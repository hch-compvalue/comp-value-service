package com.finance.dart.api.domestic.collect.financial;

import com.finance.dart.api.domestic.dart.DartClient;
import com.finance.dart.api.domestic.entity.KrFinancialReportEntity;
import com.finance.dart.api.domestic.repository.KrFinancialReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 재무 보고서 1건 수집 (연결 우선 → 없으면 별도, 회사가 쓰던 쪽을 먼저 시도해 호출을 아낀다)
 */
@Service
@RequiredArgsConstructor
public class KrFinancialCollector {

    public static final String FS_CONSOLIDATED = "CFS";
    public static final String FS_SEPARATE = "OFS";
    public static final String FS_NONE = "NONE";
    static final String API = "fnlttSinglAcntAll.json";

    private final DartClient dartClient;
    private final KrFinancialReportRepository repository;

    /** 수집 대상 종목 */
    public record Company(String stockCode, String corpCode, int fiscalMonth) {}

    /**
     * @param preferredFs 이전에 데이터가 있던 구분 (없으면 null → 연결부터)
     * @return 저장한 행 (데이터가 없으면 fs_div NONE)
     */
    public KrFinancialReportEntity collect(Company company, KrReportType.Target target, String preferredFs) {
        KrFinancialReportEntity entity = new KrFinancialReportEntity();
        entity.setStockCode(company.stockCode());
        entity.setBsnsYear(target.bsnsYear());
        entity.setReprtCode(target.type().code());
        entity.setCorpCode(company.corpCode());
        entity.setPeriodEnd(target.type().periodEnd(target.bsnsYear(), company.fiscalMonth()));
        entity.setFsDiv(FS_NONE);

        for (String fs : order(preferredFs)) {
            DartClient.Result result = dartClient.get(API, Map.of(
                    "corp_code", company.corpCode(),
                    "bsns_year", String.valueOf(target.bsnsYear()),
                    "reprt_code", target.type().code(),
                    "fs_div", fs));
            if (result.hasData()) {
                entity.setFsDiv(fs);
                KrFinancialMapper.apply(entity, result.rows(), target.type());
                break;
            }
        }
        entity.setCheckedAt(LocalDateTime.now());
        return repository.save(entity);
    }

    static List<String> order(String preferredFs) {
        return FS_SEPARATE.equals(preferredFs) ? List.of(FS_SEPARATE, FS_CONSOLIDATED) : List.of(FS_CONSOLIDATED, FS_SEPARATE);
    }
}
