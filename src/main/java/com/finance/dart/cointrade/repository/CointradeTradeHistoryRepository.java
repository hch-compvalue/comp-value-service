package com.finance.dart.cointrade.repository;

import com.finance.dart.cointrade.entity.CointradeTradeHistoryEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 코인 자동매매 거래 기록 Repository
 */
public interface CointradeTradeHistoryRepository extends JpaRepository<CointradeTradeHistoryEntity, Long> {

    /**
     * 종목별 거래 기록 조회
     * @param coinCode 코인 코드
     * @return 거래 기록 Entity 목록
     */
    List<CointradeTradeHistoryEntity> findByCoinCode(String coinCode);

    /**
     * 날짜 범위로 거래 기록 조회
     * @param start 시작 일시
     * @param end 종료 일시
     * @return 거래 기록 Entity 목록
     */
    List<CointradeTradeHistoryEntity> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end);

    /**
     * 날짜 범위로 거래 기록 조회 (페이징)
     * @param start 시작 일시
     * @param end 종료 일시
     * @param pageable 페이징 정보
     * @return 거래 기록 Entity 페이지
     */
    Page<CointradeTradeHistoryEntity> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end, Pageable pageable);

    /**
     * 종목별 + 날짜 범위 거래 기록 조회
     * @param coinCode 코인 코드
     * @param start 시작 일시
     * @param end 종료 일시
     * @return 거래 기록 Entity 목록
     */
    List<CointradeTradeHistoryEntity> findByCoinCodeAndCreatedAtBetween(
            String coinCode, LocalDateTime start, LocalDateTime end);

    /**
     * 종목별 + 날짜 범위 거래 기록 조회 (페이징)
     * @param coinCode 코인 코드
     * @param start 시작 일시
     * @param end 종료 일시
     * @param pageable 페이징 정보
     * @return 거래 기록 Entity 페이지
     */
    Page<CointradeTradeHistoryEntity> findByCoinCodeAndCreatedAtBetween(
            String coinCode, LocalDateTime start, LocalDateTime end, Pageable pageable);

    /**
     * 거래 타입별 거래 기록 조회
     * @param tradeType 거래 타입 (BUY/SELL)
     * @return 거래 기록 Entity 목록
     */
    List<CointradeTradeHistoryEntity> findByTradeType(String tradeType);

    /**
     * 종목별 + 거래 타입별 거래 기록 조회
     * @param coinCode 코인 코드
     * @param tradeType 거래 타입 (BUY/SELL)
     * @return 거래 기록 Entity 목록
     */
    List<CointradeTradeHistoryEntity> findByCoinCodeAndTradeType(String coinCode, String tradeType);

    /**
     * 최근 거래 내역 조회 (페이징)
     * @param pageable 페이징 정보
     * @return 거래 기록 Entity 페이지
     */
    Page<CointradeTradeHistoryEntity> findByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * 종목별 최근 거래 내역 조회 (페이징)
     * @param coinCode 코인 코드
     * @param pageable 페이징 정보
     * @return 거래 기록 Entity 페이지
     */
    Page<CointradeTradeHistoryEntity> findByCoinCodeOrderByCreatedAtDesc(String coinCode, Pageable pageable);

    /**
     * 특정 시점 이후 거래 기록 조회 (watermark 방식 신규 거래 감지용)
     * @param createdAt 기준 시점
     * @return 신규 거래 기록 (시간순)
     */
    List<CointradeTradeHistoryEntity> findByCreatedAtAfterOrderByCreatedAtAsc(LocalDateTime createdAt);

    /**
     * 거래 기록에 존재하는 코인 코드 목록 (중복 제거, 오름차순)
     * @return 코인 코드 목록
     */
    @Query("select distinct t.coinCode from CointradeTradeHistoryEntity t order by t.coinCode")
    List<String> findDistinctCoinCodes();
}
