package com.finance.dart.api.common.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.finance.dart.api.common.constants.Market;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 추천종목 프로파일 마스터 엔티티
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Entity
@Table(name = "tb_recommend_profile", schema = "public",
        indexes = {
                @Index(name = "idx_recommend_profile_is_active", columnList = "is_active")
        })
public class RecommendProfileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;                        // PK

    @Column(name = "profile_name", nullable = false, unique = true, length = 100)
    private String profileName;             // 프로파일명 (예: 가치투자형, 성장주형)

    @Enumerated(EnumType.STRING)
    @Column(name = "market", nullable = false, length = 2, columnDefinition = Market.COLUMN_DEFINITION)
    private Market market = Market.US;  // 시장 (US/KR, 기존 행은 US)

    @Column(name = "profile_desc", length = 500)
    private String profileDesc;             // 프로파일 설명

    @Column(name = "is_active", nullable = false, length = 1)
    private String isActive = "N";          // 활성여부 (Y: 스케줄러에서 사용, N: 미사용)

    @Column(name = "sort_order")
    private Integer sortOrder = 0;          // 정렬순서

//    @Column(name = "use_yn", nullable = false, length = 1)
//    private String useYn = "Y";             // 사용여부 (Y: 사용, N: 삭제처리)

    private LocalDateTime createdAt = LocalDateTime.now();  // 등록일시
    private LocalDateTime updatedAt = LocalDateTime.now();  // 수정일시

    /**
     * 프로파일별 스크리닝 설정 (1:1 관계)
     * - CascadeType.ALL: 프로파일 저장/수정/삭제 시 설정도 함께 처리
     * - orphanRemoval: 프로파일 삭제 시 설정도 삭제
     */
    @JsonIgnore
    @OneToOne(mappedBy = "profile", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private RecommendProfileConfigEntity config;

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /** 시장 미지정 저장 방지 — 기존 저장 경로(미국)는 US */
    @PrePersist
    private void defaultMarket() {
        if (market == null) market = Market.US;
    }
}
