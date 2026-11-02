package com.finance.dart.api.common.repository;

import com.finance.dart.api.common.constants.Market;
import com.finance.dart.api.common.entity.EvaluationHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 종목 평가 이력 Repository
 */
public interface EvaluationHistoryRepository extends JpaRepository<EvaluationHistoryEntity, Long> {

    /**
     * 특정 일자·시장의 평가 이력 조회 (US/KR 이력은 같은 테이블 — 항상 시장을 지정)
     */
    List<EvaluationHistoryEntity> findBySnapshotDateAndMarket(LocalDate snapshotDate, Market market);

    /**
     * 시장별 최신 평가 일자 1행
     */
    Optional<EvaluationHistoryEntity> findFirstByMarketOrderBySnapshotDateDesc(Market market);

    /**
     * 같은 날 재평가 시 기존 스냅샷 삭제 (멱등 저장용)
     * - 벌크 DELETE로 즉시 실행 → 이어지는 insert보다 먼저 수행 (Hibernate insert-before-delete 순서 이슈 회피)
     */
    @Modifying
    @Query("DELETE FROM EvaluationHistoryEntity e WHERE e.snapshotDate = :snapshotDate AND e.market = :market")
    void deleteSnapshot(@Param("snapshotDate") LocalDate snapshotDate, @Param("market") Market market);
}
