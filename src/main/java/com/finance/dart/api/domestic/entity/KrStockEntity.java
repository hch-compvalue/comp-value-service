package com.finance.dart.api.domestic.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 국내 종목 마스터 (보통주만 — 우선주·스팩·ETF·리츠 제외)
 * - KIS 종목 마스터 파일 + OpenDart 고유번호로 매일 갱신 (KR_DAILY_PRICE 배치 시작 시)
 * - 마스터에서 사라진 종목(상장폐지 등)은 지우지 않고 active=N (일봉 이력 보존)
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "tb_kr_stock", schema = "public")
public class KrStockEntity {

    @Id
    @Column(name = "stock_code", length = 6)
    private String stockCode;               // 단축코드 6자리

    @Column(name = "corp_code", length = 8)
    private String corpCode;                // OpenDart 고유번호 (매핑 안 되면 null)

    @Column(name = "stock_name", nullable = false, length = 100)
    private String stockName;

    @Column(name = "exchange", nullable = false, length = 10)
    private String exchange;                // KOSPI / KOSDAQ

    @Column(name = "index_large", length = 4)
    private String indexLarge;              // KIS 지수업종 대분류 코드

    @Column(name = "index_mid", length = 4)
    private String indexMid;

    @Column(name = "index_small", length = 4)
    private String indexSmall;

    @Column(name = "listed_date")
    private LocalDate listedDate;

    @Column(name = "fiscal_month", length = 2)
    private String fiscalMonth;             // 결산월 (12가 아닌 회사는 분기 정규화 시 주의)

    @Column(name = "trading_halt", nullable = false, length = 1)
    private String tradingHalt;             // 거래정지 Y/N

    @Column(name = "administrative", nullable = false, length = 1)
    private String administrative;          // 관리종목 Y/N

    @Column(name = "active", nullable = false, length = 1)
    private String active;                  // 마스터에 있으면 Y, 사라지면 N

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
