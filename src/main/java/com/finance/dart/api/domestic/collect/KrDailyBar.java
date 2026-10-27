package com.finance.dart.api.domestic.collect;

import java.time.LocalDate;

/**
 * 국내 종목 일봉 1행 (KIS 수정주가, 원 단위)
 */
public record KrDailyBar(LocalDate tradeDate, int open, int high, int low, int close, long volume, long tradeAmount) {
}
