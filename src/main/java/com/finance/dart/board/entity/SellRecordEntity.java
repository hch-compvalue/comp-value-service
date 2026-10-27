package com.finance.dart.board.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.finance.dart.member.entity.MemberEntity;
import com.finance.dart.api.common.constants.Market;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 매도기록 테이블
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Entity
@Table(
        name = "sell_record",
        schema = "public"
)
public class SellRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;                // PK

    @JsonIgnore // JSON 직렬화 시 순환 참조 방지
    @ManyToOne(fetch = FetchType.LAZY) // 여러 거래가 한 회원에 속할 수 있음 (N:1)
    @JoinColumn(name = "member_id", nullable = false) // FK 컬럼명 지정
    private MemberEntity member; // Member 테이블의 PK(id)를 참조

    @NotNull
    private String symbol;          // 티커

    @Enumerated(EnumType.STRING)
    @Column(name = "market", nullable = false, length = 2, columnDefinition = Market.COLUMN_DEFINITION)
    private Market market = Market.US;  // 시장 (US/KR, 기존 행은 US)

    private String companyName;     // 기업명

    private String sellDate;        // 매도일자
    private Double sellPrice;       // 매도가격(단가)
    private Integer sellQty;        // 매도수량

    private Double realizedPnl;     // 실현손익

    private Double buyExchangeRateAtTrade;  // 매수체결당시 환율
    private Double sellExchangeRateAtTrade; // 매도체결당시 환율

    @Column(length = 500)
    private String rmk;             // 비고

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();

    /** 시장 미지정 저장 방지 — 기존 저장 경로(미국)는 US */
    @PrePersist
    private void defaultMarket() {
        if (market == null) market = Market.US;
    }
}
