package com.finance.dart.board.repository;

import com.finance.dart.board.entity.SellRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 매도기록 Repository
 */
public interface SellRecordRepository extends JpaRepository<SellRecordEntity, Long> {

    /**
     * 회원ID 에 해당하는 데이터 목록 조회
     * @param memberId
     * @return
     */
    List<SellRecordEntity> findByMember_Id(Long memberId);

}
