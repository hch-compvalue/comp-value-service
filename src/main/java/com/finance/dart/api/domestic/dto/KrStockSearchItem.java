package com.finance.dart.api.domestic.dto;

/**
 * 국내 종목 검색 결과 — 미국 검색(FMP search-symbol)과 같은 필드명 (화면 공용)
 * @param symbol 종목코드 6자리
 */
public record KrStockSearchItem(String symbol, String name, String currency, String exchange, String exchangeFullName) {
}
