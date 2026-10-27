package com.finance.dart.api.domestic.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 국내 지수 일봉 (KOSPI 0001 / KOSDAQ 1001) — 베타 계산·차트 비교용
 * - 대량 저장은 KrPriceJdbcRepository, DB 백업에서 데이터 제외(일봉과 동일)
 */
@Data
@NoArgsConstructor
@Entity
@IdClass(KrIndexDailyEntity.Key.class)
@Table(name = "tb_kr_index_daily", schema = "public")
public class KrIndexDailyEntity {

    @Id
    @Column(name = "index_code", length = 4)
    private String indexCode;

    @Id
    @Column(name = "trade_date")
    private LocalDate tradeDate;

    @Column(name = "open_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal openValue;

    @Column(name = "high_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal highValue;

    @Column(name = "low_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal lowValue;

    @Column(name = "close_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal closeValue;

    @Data
    @NoArgsConstructor
    public static class Key implements Serializable {
        private String indexCode;
        private LocalDate tradeDate;
    }
}
