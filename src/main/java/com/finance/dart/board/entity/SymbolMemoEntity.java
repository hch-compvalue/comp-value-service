package com.finance.dart.board.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.finance.dart.member.entity.MemberEntity;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 보유종목 종목 메모 테이블 (회원 + 심볼 단위, 매수 건 비고와 별개)
 * - 해당 심볼의 보유 매수 건(tran_record)이 모두 없어지면 함께 삭제
 */
@Data
@Entity
@Table(name = "tran_record_symbol_memo",
       schema = "public",
       uniqueConstraints = {
            @UniqueConstraint(name = "uk_symbol_memo_member_symbol", columnNames = {"member_id", "symbol"})
       }
)
public class SymbolMemoEntity {

    public static final int MEMO_MAX_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;               // PK

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private MemberEntity member;

    @Column(nullable = false)
    private String symbol;         // 티커

    @Column(length = MEMO_MAX_LENGTH)
    private String memo;           // 종목 메모

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();
}
