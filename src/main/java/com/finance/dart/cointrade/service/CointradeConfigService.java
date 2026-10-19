package com.finance.dart.cointrade.service;

import com.finance.dart.cointrade.consts.CoinTraderProgramConfig;
import com.finance.dart.cointrade.dto.CointradeTradeHistoryDto;
import com.finance.dart.cointrade.entity.CointradeTradeHistoryEntity;
import com.finance.dart.cointrade.repository.CointradeTradeHistoryRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
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
            String market,
            boolean includeLegacy,
            Pageable pageable) {

        // 종목·매매구분·기간 조건을 있는 것만 조합 (정렬은 pageable, 기본 createdAt DESC)
        Specification<CointradeTradeHistoryEntity> spec = (root, query, cb) -> {
            List<Predicate> conditions = new ArrayList<>();
            if (coinCode != null && !coinCode.isBlank()) conditions.add(cb.equal(root.get("coinCode"), coinCode));
            if (tradeType != null && !tradeType.isBlank()) conditions.add(cb.equal(root.get("tradeType"), tradeType));
            if (startDate != null) conditions.add(cb.greaterThanOrEqualTo(root.get("createdAt"), startDate));
            if (endDate != null) conditions.add(cb.lessThanOrEqualTo(root.get("createdAt"), endDate));
            // 시장: 코인 = KRW-xxx, 국내주식 = 그 외(6자리 종목코드)
            Predicate crypto = cb.like(root.get("coinCode"), CoinTraderProgramConfig.CRYPTO_CODE_PREFIX + "%");
            if (CoinTraderProgramConfig.MARKET_CRYPTO.equalsIgnoreCase(market)) conditions.add(crypto);
            else if (CoinTraderProgramConfig.MARKET_KRX.equalsIgnoreCase(market)) conditions.add(cb.not(crypto));
            // 기본은 현재 전략 기록만 (구 v4 단타 기록은 includeLegacy=true일 때 포함)
            if (!includeLegacy) conditions.add(root.get("reason").in(CoinTraderProgramConfig.STRATEGY_TRADE_REASONS));
            return cb.and(conditions.toArray(new Predicate[0]));
        };
        Page<CointradeTradeHistoryEntity> entities = tradeHistoryRepository.findAll(spec, pageable);

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
