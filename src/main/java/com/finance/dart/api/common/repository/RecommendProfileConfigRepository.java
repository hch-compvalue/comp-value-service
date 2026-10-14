package com.finance.dart.api.common.repository;

import com.finance.dart.api.common.entity.RecommendProfileConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 프로파일별 스크리닝 설정 Repository
 */
public interface RecommendProfileConfigRepository extends JpaRepository<RecommendProfileConfigEntity, Long> {

    /**
     * 프로파일 ID로 설정 조회
     */
    Optional<RecommendProfileConfigEntity> findByProfile_Id(Long profileId);
}
