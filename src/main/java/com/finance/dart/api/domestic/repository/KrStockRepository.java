package com.finance.dart.api.domestic.repository;

import com.finance.dart.api.domestic.entity.KrStockEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KrStockRepository extends JpaRepository<KrStockEntity, String> {

    List<KrStockEntity> findByActiveOrderByStockCode(String active);
}
