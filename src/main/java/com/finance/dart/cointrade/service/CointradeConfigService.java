package com.finance.dart.cointrade.service;

import com.finance.dart.cointrade.dto.CointradeTradeHistoryDto;
import com.finance.dart.cointrade.entity.CointradeTradeHistoryEntity;
import com.finance.dart.cointrade.repository.CointradeTradeHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 코인 자동매매 거래 기록 조회 서비스
 * (v5.0 전략 전환 후 레거시 설정/매매/업비트 연동 기능 제거 — 거래 기록 조회만 유지)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CointradeConfigService {

    private final CointradeTradeHistoryRepository tradeHistoryRepository;

    /**
     * 거래 기록 조회 (페이징)
     */
    @Transactional(readOnly = true)
    public Page<CointradeTradeHistoryDto> getTradeHistory(
            LocalDateTime startDate,
            LocalDateTime endDate,
            String coinCode,
            String tradeType,
            Pageable pageable) {

        Page<CointradeTradeHistoryEntity> entities;

        // 조건별 조회
        if (coinCode != null && !coinCode.isEmpty() && startDate != null && endDate != null) {
            // 종목 + 날짜 범위
            entities = tradeHistoryRepository.findByCoinCodeAndCreatedAtBetween(
                    coinCode, startDate, endDate, pageable);
        } else if (coinCode != null && !coinCode.isEmpty()) {
            // 종목별 조회
            entities = tradeHistoryRepository.findByCoinCodeOrderByCreatedAtDesc(
                    coinCode, pageable);
        } else if (startDate != null && endDate != null) {
            // 날짜 범위 조회
            entities = tradeHistoryRepository.findByCreatedAtBetween(
                    startDate, endDate, pageable);
        } else {
            // 전체 조회
            entities = tradeHistoryRepository.findByOrderByCreatedAtDesc(pageable);
        }

        return entities.map(entity -> CointradeTradeHistoryDto.builder()
                .id(entity.getId())
                .coinCode(entity.getCoinCode())
                .tradeType(entity.getTradeType())
                .price(entity.getPrice())
                .quantity(entity.getQuantity())
                .totalAmount(entity.getTotalAmount())
                .reason(entity.getReason())
                .profitLoss(entity.getProfitLoss())
                .profitLossRate(entity.getProfitLossRate())
                .momentumScore(entity.getMomentumScore())
                .mlConfidence(entity.getMlConfidence())
                .entryReason(entity.getEntryReason())
                .holdDurationSec(entity.getHoldDurationSec())
                .createdAt(entity.getCreatedAt())
                .build());
    }

    /**
     * 거래 기록에 존재하는 코인 코드 목록 조회 (중복 제거, 오름차순)
     */
    @Transactional(readOnly = true)
    public List<String> getTradeHistoryCoinCodes() {
        return tradeHistoryRepository.findDistinctCoinCodes();
    }
}
