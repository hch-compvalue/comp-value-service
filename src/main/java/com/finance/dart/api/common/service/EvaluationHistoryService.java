package com.finance.dart.api.common.service;

import com.finance.dart.api.common.entity.EvaluationHistoryEntity;
import com.finance.dart.api.common.dto.evaluation.StockEvaluationResponse;
import com.finance.dart.api.common.repository.EvaluationHistoryRepository;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 종목 평가 이력 서비스
 * - 일별 자동 전수 평가 결과를 일자별로 DB에 영속화 (매수후보 목록 + 수익률 추적 기반)
 */
@Slf4j
@Service
@AllArgsConstructor
public class EvaluationHistoryService {

    private final EvaluationHistoryRepository evaluationHistoryRepository;

    /**
     * 평가 스냅샷 저장 (같은 날 재평가는 교체 = 멱등)
     * - ERROR 응답(평가 실패)은 제외
     * @param responses 평가 결과 리스트
     * @param symbolProfiles 심볼 → 추천한 프로파일명 목록 (평가 응답 심볼은 요청 심볼 그대로라 그대로 조회, 없으면 태그 null)
     * @param symbolRoe 심볼 → ROE TTM (투자 순위 점수용, 없으면 그 종목 순위 점수 null)
     * @return 저장된 종목 수
     */
    @Transactional
    public int saveSnapshot(List<StockEvaluationResponse> responses, Map<String, List<String>> symbolProfiles,
                            Map<String, Double> symbolRoe) {
        LocalDate today = LocalDate.now();

        // 벌크 DELETE 즉시 실행 → 이어지는 insert보다 먼저 (Hibernate insert-before-delete 순서 이슈 회피)
        evaluationHistoryRepository.deleteSnapshot(today);

        List<EvaluationHistoryEntity> entities = new ArrayList<>();
        for (StockEvaluationResponse r : responses) {
            if (r == null || r.getSymbol() == null || r.getSymbol().isEmpty()) continue;
            if ("ERROR".equals(r.getGrade())) continue;  // 평가 실패 종목 제외

            EvaluationHistoryEntity e = new EvaluationHistoryEntity();
            e.setSnapshotDate(today);
            e.setSymbol(r.getSymbol());
            e.setCompanyName(r.getCompanyName());
            e.setSector(r.getSector());
            e.setInvestmentSignal(r.getInvestmentSignal());
            e.setValueGrade(r.getValueGrade());
            e.setValueScore(r.getValueScore());
            e.setTimingSignal(r.getTimingSignal());
            e.setTimingScore(r.getTimingScore());
            e.setTotalScore(r.getTotalScore());
            e.setGrade(r.getGrade());
            e.setCurrentPrice(toBigDecimal(r.getCurrentPrice()));
            e.setFairValue(toBigDecimal(r.getFairValue()));
            e.setPurchasePrice(toBigDecimal(r.getPurchasePrice()));
            // 발행주식수는 계산 상세에만 있음 (분할·병합 판별용, 미제공 종목은 null)
            e.setSharesOutstanding(r.getResultDetail() != null
                    ? toBigDecimal(r.getResultDetail().get발행주식수()) : null);
            e.setSourceProfiles(joinProfiles(symbolProfiles.get(r.getSymbol())));
            e.setRoe(symbolRoe.get(r.getSymbol()));
            e.setReturn1wPct(r.getReturn1wPct());
            e.setCreatedAt(LocalDateTime.now());
            entities.add(e);
        }

        // 그날 후보 전체 기준 백분위 → 투자 순위 점수 (평가 실패 종목은 이미 제외됨)
        InvestmentRankScorer.assign(entities);

        evaluationHistoryRepository.saveAll(entities);
        log.info("[평가 이력] DB 저장 완료 - 일자: {}, 종목 수: {} (평가 실패 제외)", today, entities.size());
        return entities.size();
    }

    /**
     * 특정 일자 평가 이력 조회 (없으면 최신 일자)
     */
    @Transactional(readOnly = true)
    public List<EvaluationHistoryEntity> getSnapshot(LocalDate date) {
        LocalDate target = date;
        if (target == null) {
            target = evaluationHistoryRepository.findFirstByOrderBySnapshotDateDesc()
                    .map(EvaluationHistoryEntity::getSnapshotDate)
                    .orElse(null);
        }
        if (target == null) return new ArrayList<>();
        return evaluationHistoryRepository.findBySnapshotDate(target);
    }

    private String joinProfiles(List<String> profiles) {
        if (profiles == null || profiles.isEmpty()) return null;
        return String.join(EvaluationHistoryEntity.PROFILE_DELIMITER, profiles);
    }

    private BigDecimal toBigDecimal(String value) {
        if (value == null || value.isEmpty() || "N/A".equals(value)) return null;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
