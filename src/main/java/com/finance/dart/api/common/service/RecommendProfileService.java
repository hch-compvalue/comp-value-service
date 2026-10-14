package com.finance.dart.api.common.service;

import com.finance.dart.api.common.dto.RecommendProfileDto;
import com.finance.dart.api.common.entity.RecommendProfileConfigEntity;
import com.finance.dart.api.common.entity.RecommendProfileEntity;
import com.finance.dart.api.common.repository.RecommendProfileConfigRepository;
import com.finance.dart.api.common.repository.RecommendProfileRepository;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 추천종목 프로파일 서비스
 */
@Slf4j
@AllArgsConstructor
@Service
public class RecommendProfileService {

    private final RecommendProfileRepository profileRepository;
    private final RecommendProfileConfigRepository configRepository;

    /**
     * 프로파일 등록 (마스터 + 설정)
     */
    @Transactional
    public RecommendProfileDto regiProfile(RecommendProfileDto dto) {

        // 프로파일명 중복 체크
        Optional<RecommendProfileEntity> existingProfile = profileRepository.findByProfileName(dto.getProfileName());
        if (existingProfile.isPresent()) {
            throw new RuntimeException("이미 존재하는 프로파일명입니다: " + dto.getProfileName());
        }

        // 프로파일 마스터 저장
        RecommendProfileEntity profileEntity = new RecommendProfileEntity();
        profileEntity.setProfileName(dto.getProfileName());
        profileEntity.setProfileDesc(dto.getProfileDesc());
        profileEntity.setIsActive(dto.getIsActive() != null ? dto.getIsActive() : "N");
        profileEntity.setSortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0);

        RecommendProfileEntity savedProfile = profileRepository.save(profileEntity);

        // 프로파일 설정 저장
        RecommendProfileConfigEntity configEntity = new RecommendProfileConfigEntity();
        configEntity.setProfile(savedProfile);
        setConfigFields(configEntity, dto);

        configRepository.save(configEntity);

        return toDto(savedProfile, configEntity);
    }

    /**
     * 프로파일 수정 (마스터 + 설정)
     */
    @Transactional
    public RecommendProfileDto modiProfile(RecommendProfileDto dto) {

        if (dto.getId() == null) {
            throw new RuntimeException("프로파일 ID가 필요합니다.");
        }

        // 프로파일 조회
        Optional<RecommendProfileEntity> profileOpt = profileRepository.findById(dto.getId());
        if (profileOpt.isEmpty()) {
            return null;
        }

        RecommendProfileEntity profileEntity = profileOpt.get();

        // 프로파일명 변경 시 중복 체크
        if (dto.getProfileName() != null && !dto.getProfileName().equals(profileEntity.getProfileName())) {
            Optional<RecommendProfileEntity> existingProfile = profileRepository.findByProfileName(dto.getProfileName());
            if (existingProfile.isPresent()) {
                throw new RuntimeException("이미 존재하는 프로파일명입니다: " + dto.getProfileName());
            }
            profileEntity.setProfileName(dto.getProfileName());
        }

        // 프로파일 마스터 수정
        if (dto.getProfileDesc() != null) profileEntity.setProfileDesc(dto.getProfileDesc());
        if (dto.getIsActive() != null) profileEntity.setIsActive(dto.getIsActive());
        if (dto.getSortOrder() != null) profileEntity.setSortOrder(dto.getSortOrder());
        profileEntity.setUpdatedAt(LocalDateTime.now());

        RecommendProfileEntity savedProfile = profileRepository.save(profileEntity);

        // 프로파일 설정 조회 및 수정
        Optional<RecommendProfileConfigEntity> configOpt = configRepository.findByProfile_Id(dto.getId());
        RecommendProfileConfigEntity configEntity;

        if (configOpt.isPresent()) {
            configEntity = configOpt.get();
            setConfigFields(configEntity, dto);
            configEntity.setUpdatedAt(LocalDateTime.now());
        } else {
            // 설정이 없으면 새로 생성
            configEntity = new RecommendProfileConfigEntity();
            configEntity.setProfile(savedProfile);
            setConfigFields(configEntity, dto);
        }

        configRepository.save(configEntity);

        return toDto(savedProfile, configEntity);
    }

    /**
     * 프로파일 삭제 (마스터 삭제 시 설정도 함께 삭제 - Cascade)
     */
    @Transactional
    public void delProfile(Long id) {
        if (id != null) {
            profileRepository.deleteById(id);
        }
    }

    /**
     * 프로파일 목록 조회 (전체)
     */
    public List<RecommendProfileDto> getProfileList() {
        List<RecommendProfileEntity> profileList = profileRepository.findAllByOrderBySortOrder();

        return profileList.stream()
                .map(profile -> {
                    Optional<RecommendProfileConfigEntity> configOpt = configRepository.findByProfile_Id(profile.getId());
                    return toDto(profile, configOpt.orElse(null));
                })
                .collect(Collectors.toList());
    }

    /**
     * 설정 필드 세팅 헬퍼
     */
    private void setConfigFields(RecommendProfileConfigEntity configEntity, RecommendProfileDto dto) {
        // Stock Screener 조건 - 슬라이더 필드는 null 허용 (비활성 시 null로 클리어)
        configEntity.setMarketCapMin(dto.getMarketCapMin());
        configEntity.setMarketCapMax(dto.getMarketCapMax());
        configEntity.setPriceMin(dto.getPriceMin());
        configEntity.setPriceMax(dto.getPriceMax());
        configEntity.setBetaMin(dto.getBetaMin());
        configEntity.setBetaMax(dto.getBetaMax());
        configEntity.setVolumeMin(dto.getVolumeMin());
        configEntity.setVolumeMax(dto.getVolumeMax());

        // Stock Screener 조건 - 비슬라이더 필드는 null 체크 유지
        if (dto.getIsEtf() != null) configEntity.setIsEtf(dto.getIsEtf());
        if (dto.getIsFund() != null) configEntity.setIsFund(dto.getIsFund());
        if (dto.getIsActivelyTrading() != null) configEntity.setIsActivelyTrading(dto.getIsActivelyTrading());
        if (dto.getExchange() != null) configEntity.setExchange(dto.getExchange());
        if (dto.getScreenerLimit() != null) configEntity.setScreenerLimit(dto.getScreenerLimit());
        if (dto.getExcludeNonCommonStock() != null) configEntity.setExcludeNonCommonStock(dto.getExcludeNonCommonStock());
        configEntity.setSectorQuotaPct(dto.getSectorQuotaPct());  // 슬라이더류 필드 - null 허용 (비활성 시 클리어)
        if (dto.getSector() != null) configEntity.setSector(dto.getSector());
        if (dto.getIndustry() != null) configEntity.setIndustry(dto.getIndustry());
        if (dto.getCountry() != null) configEntity.setCountry(dto.getCountry());

        // 저평가 필터링 조건 - 슬라이더 필드는 null 허용
        configEntity.setPeRatioMin(dto.getPeRatioMin());
        configEntity.setPeRatioMax(dto.getPeRatioMax());
        configEntity.setPbRatioMin(dto.getPbRatioMin());
        configEntity.setPbRatioMax(dto.getPbRatioMax());
        configEntity.setRoeMin(dto.getRoeMin());
        configEntity.setRoeMax(dto.getRoeMax());
        configEntity.setDebtEquityMin(dto.getDebtEquityMin());
        configEntity.setDebtEquityMax(dto.getDebtEquityMax());

        // 성장률 필터링 조건
        configEntity.setRevenueGrowthMin(dto.getRevenueGrowthMin());
        configEntity.setRevenueGrowthMax(dto.getRevenueGrowthMax());
        configEntity.setNetIncomeGrowthMin(dto.getNetIncomeGrowthMin());
        configEntity.setNetIncomeGrowthMax(dto.getNetIncomeGrowthMax());
    }

    /**
     * Entity -> DTO 변환
     */
    private RecommendProfileDto toDto(RecommendProfileEntity profile, RecommendProfileConfigEntity config) {
        RecommendProfileDto dto = new RecommendProfileDto();

        // 프로파일 마스터
        dto.setId(profile.getId());
        dto.setProfileName(profile.getProfileName());
        dto.setProfileDesc(profile.getProfileDesc());
        dto.setIsActive(profile.getIsActive());
        dto.setSortOrder(profile.getSortOrder());
        dto.setCreatedAt(profile.getCreatedAt() != null ? profile.getCreatedAt().toString() : null);
        dto.setUpdatedAt(profile.getUpdatedAt() != null ? profile.getUpdatedAt().toString() : null);

        // 설정
        if (config != null) {
            dto.setMarketCapMin(config.getMarketCapMin());
            dto.setMarketCapMax(config.getMarketCapMax());
            dto.setBetaMax(config.getBetaMax());
            dto.setVolumeMin(config.getVolumeMin());
            dto.setIsEtf(config.getIsEtf());
            dto.setIsFund(config.getIsFund());
            dto.setIsActivelyTrading(config.getIsActivelyTrading());
            dto.setExchange(config.getExchange());
            dto.setScreenerLimit(config.getScreenerLimit());
            dto.setExcludeNonCommonStock(config.getExcludeNonCommonStock());
            dto.setSectorQuotaPct(config.getSectorQuotaPct());
            dto.setPriceMin(config.getPriceMin());
            dto.setPriceMax(config.getPriceMax());
            dto.setBetaMin(config.getBetaMin());
            dto.setVolumeMax(config.getVolumeMax());
            dto.setSector(config.getSector());
            dto.setIndustry(config.getIndustry());
            dto.setCountry(config.getCountry());
            dto.setPeRatioMin(config.getPeRatioMin());
            dto.setPeRatioMax(config.getPeRatioMax());
            dto.setPbRatioMin(config.getPbRatioMin());
            dto.setPbRatioMax(config.getPbRatioMax());
            dto.setRoeMin(config.getRoeMin());
            dto.setRoeMax(config.getRoeMax());
            dto.setDebtEquityMin(config.getDebtEquityMin());
            dto.setDebtEquityMax(config.getDebtEquityMax());
            dto.setRevenueGrowthMin(config.getRevenueGrowthMin());
            dto.setRevenueGrowthMax(config.getRevenueGrowthMax());
            dto.setNetIncomeGrowthMin(config.getNetIncomeGrowthMin());
            dto.setNetIncomeGrowthMax(config.getNetIncomeGrowthMax());
        }

        return dto;
    }
}
