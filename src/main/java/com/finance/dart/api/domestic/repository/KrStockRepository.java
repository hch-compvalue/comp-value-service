package com.finance.dart.api.domestic.repository;

import com.finance.dart.api.domestic.entity.KrStockEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface KrStockRepository extends JpaRepository<KrStockEntity, String> {

    List<KrStockEntity> findByActiveOrderByStockCode(String active);

    /** 업종(KSIC)을 조회한 적 없거나 기준 시각 이전에 조회한 종목 */
    @Query("select s from KrStockEntity s where s.active = :active and (s.ksicCheckedAt is null or s.ksicCheckedAt < :before) order by s.stockCode")
    List<KrStockEntity> findKsicTargets(@Param("active") String active, @Param("before") LocalDateTime before);
}
