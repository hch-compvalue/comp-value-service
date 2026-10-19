package com.finance.dart.board.repository;

import com.finance.dart.board.entity.TranRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 거래기록 Repository
 */
public interface TranRecordRepository extends JpaRepository<TranRecordEntity, Long> {

    /**
     * 회원ID 에 해당하는 데이터 목록 조회
     * @param memberId
     * @return
     */
    List<TranRecordEntity> findByMember_Id(Long memberId);

    /**
     * 회원이 해당 심볼을 보유 중인지 (매수 건 1건 이상)
     */
    boolean existsByMember_IdAndSymbol(Long memberId, String symbol);

}
