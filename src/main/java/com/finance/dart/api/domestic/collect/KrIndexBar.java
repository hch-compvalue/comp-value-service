package com.finance.dart.api.domestic.collect;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 국내 지수 일봉 1행
 */
public record KrIndexBar(LocalDate tradeDate, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {
}
