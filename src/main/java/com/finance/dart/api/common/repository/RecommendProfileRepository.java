package com.finance.dart.api.common.repository;

import com.finance.dart.api.common.constants.Market;
import com.finance.dart.api.common.entity.RecommendProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 추천종목 프로파일 Repository
 */
public interface RecommendProfileRepository extends JpaRepository<RecommendProfileEntity, Long> {

    /**
     * 프로파일명으로 조회
     */
    Optional<RecommendProfileEntity> findByProfileName(String profileName);

    /**
     * 시장별 활성 프로파일 조회 (스케줄러에서 사용 — 미국 배치가 국내 프로파일을 쓰지 않게 항상 시장 지정)
     */
    List<RecommendProfileEntity> findByIsActiveAndMarketOrderBySortOrder(String isActive, Market market);

    /**
     * 시장별 전체 프로파일 목록 (정렬순서)
     */
    List<RecommendProfileEntity> findAllByMarketOrderBySortOrder(Market market);
}
