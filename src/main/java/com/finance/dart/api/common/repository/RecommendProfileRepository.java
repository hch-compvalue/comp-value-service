package com.finance.dart.api.common.repository;

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
     * 활성화된 프로파일 조회 (스케줄러에서 사용)
     */
    List<RecommendProfileEntity> findByIsActiveOrderBySortOrder(String isActive);

    /**
     * 전체 프로파일 목록 조회 (정렬순서)
     */
    List<RecommendProfileEntity> findAllByOrderBySortOrder();
}
