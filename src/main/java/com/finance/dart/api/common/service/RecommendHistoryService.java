package com.finance.dart.api.common.service;

import com.finance.dart.api.common.constants.Market;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finance.dart.api.common.entity.RecommendHistoryEntity;
import com.finance.dart.api.common.repository.RecommendHistoryRepository;
import com.finance.dart.api.common.service.schedule.RecommendedStocksProcessor.RecommendedStockData;
import com.finance.dart.common.util.ConvertUtil;
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
 * 추천 종목 이력 서비스
 * - 스케줄 결과를 일자별로 DB에 영속화 (Redis는 캐시, DB는 durable + 수익률 추적 기반)
 * - Redis miss 시 최신 스냅샷 복원용 조회 제공
 */
@Slf4j
@Service
@AllArgsConstructor
public class RecommendHistoryService {

    private final RecommendHistoryRepository recommendHistoryRepository;
    private final ObjectMapper objectMapper;

    /**
     * 프로파일 스냅샷 저장 (같은 날 재실행은 교체 = 멱등)
     * @param profileName 프로파일명
     * @param stocks 저장할 추천 종목 맵 (symbol -> data)
     */
    @Transactional
    public void saveSnapshot(String profileName, Map<String, RecommendedStockData> stocks) {
        saveSnapshot(profileName, stocks, Market.US);
    }

    /**
     * @param market 시장 (국내 프로파일은 이름이 달라 같은 날 삭제 범위가 겹치지 않는다)
     */
    @Transactional
    public void saveSnapshot(String profileName, Map<String, RecommendedStockData> stocks, Market market) {
        LocalDate today = LocalDate.now();

        // 같은 날 동일 프로파일 기존 스냅샷 제거 (멱등 보장)
        // 벌크 DELETE는 즉시 DB에 반영 → 이어지는 insert보다 먼저 실행되어 유니크 충돌 방지
        recommendHistoryRepository.deleteSnapshot(today, profileName);

        List<RecommendHistoryEntity> entities = new ArrayList<>();
        for (RecommendedStockData data : stocks.values()) {
            RecommendHistoryEntity entity = new RecommendHistoryEntity();
            entity.setSnapshotDate(today);
            entity.setProfileName(profileName);
            entity.setMarket(market);
            entity.setSymbol(data.symbol());
            entity.setCompanyName(data.companyName());
            entity.setPrice(data.price() != null ? BigDecimal.valueOf(data.price()) : null);
            entity.setSector(data.sector());
            entity.setDataJson(toJson(data));
            entity.setCreatedAt(LocalDateTime.now());
            entities.add(entity);
        }

        recommendHistoryRepository.saveAll(entities);
        log.info("[추천 이력] 프로파일 '{}' DB 저장 완료 - 일자: {}, 종목 수: {}", profileName, today, entities.size());
    }

    /**
     * 프로파일의 최신 스냅샷 복원 (Redis miss 시 읽기 폴백)
     * @return 최신 일자의 추천 종목 리스트 (없으면 빈 리스트)
     */
    @Transactional(readOnly = true)
    public List<RecommendedStockData> loadLatestSnapshot(String profileName) {
        List<RecommendedStockData> result = new ArrayList<>();

        LocalDate latestDate = recommendHistoryRepository
                .findFirstByProfileNameOrderBySnapshotDateDesc(profileName)
                .map(RecommendHistoryEntity::getSnapshotDate)
                .orElse(null);
        if (latestDate == null) {
            return result;
        }

        List<RecommendHistoryEntity> rows =
                recommendHistoryRepository.findBySnapshotDateAndProfileName(latestDate, profileName);
        for (RecommendHistoryEntity row : rows) {
            RecommendedStockData data = fromJson(row.getDataJson());
            if (data != null) {
                result.add(data);
            }
        }
        log.debug("[추천 이력] 프로파일 '{}' DB 폴백 로드 - 일자: {}, 종목 수: {}", profileName, latestDate, result.size());
        return result;
    }

    private String toJson(RecommendedStockData data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            log.warn("[추천 이력] JSON 직렬화 실패 (symbol={}): {}", data.symbol(), e.getMessage());
            return null;
        }
    }

    private RecommendedStockData fromJson(String json) {
        if (json == null || json.isEmpty()) return null;
        // Redis 읽기 경로와 동일하게 Gson(ConvertUtil)으로 역직렬화 (record 2-생성자 Jackson 이슈 회피)
        RecommendedStockData data = ConvertUtil.parseObject(json, RecommendedStockData.class);
        if (data == null) {
            log.warn("[추천 이력] JSON 역직렬화 실패 (null 반환)");
        }
        return data;
    }
}
