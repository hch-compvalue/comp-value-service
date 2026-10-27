package com.finance.dart.api.domestic.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * 국내 종목 일봉 (KIS 수정주가)
 * - 테이블 생성(ddl update)과 조회용. 대량 저장은 KrPriceJdbcRepository(배치 upsert)
 * - DB 백업에서 데이터는 제외(구조만) — KIS에서 다시 받을 수 있고, 비어 있으면 배치가 5년치를 다시 채운다
 */
@Data
@NoArgsConstructor
@Entity
@IdClass(KrDailyPriceEntity.Key.class)
@Table(name = "tb_kr_daily_price", schema = "public")
public class KrDailyPriceEntity {

    @Id
    @Column(name = "stock_code", length = 6)
    private String stockCode;

    @Id
    @Column(name = "trade_date")
    private LocalDate tradeDate;

    @Column(name = "open_price", nullable = false)
    private Integer openPrice;

    @Column(name = "high_price", nullable = false)
    private Integer highPrice;

    @Column(name = "low_price", nullable = false)
    private Integer lowPrice;

    @Column(name = "close_price", nullable = false)
    private Integer closePrice;

    @Column(name = "volume", nullable = false)
    private Long volume;                    // 거래량 (주)

    @Column(name = "trade_amount", nullable = false)
    private Long tradeAmount;               // 거래대금 (원)

    @Data
    @NoArgsConstructor
    public static class Key implements Serializable {
        private String stockCode;
        private LocalDate tradeDate;
    }
}
