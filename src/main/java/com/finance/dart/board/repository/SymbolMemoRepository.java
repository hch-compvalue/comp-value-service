package com.finance.dart.board.repository;

import com.finance.dart.board.entity.SymbolMemoEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 보유종목 종목 메모 Repository
 */
public interface SymbolMemoRepository extends JpaRepository<SymbolMemoEntity, Long> {

    List<SymbolMemoEntity> findByMember_Id(Long memberId);

    Optional<SymbolMemoEntity> findByMember_IdAndSymbol(Long memberId, String symbol);

    void deleteByMember_IdAndSymbol(Long memberId, String symbol);
}
