package com.finance.dart.api.abroad.enums;


import org.springframework.http.HttpMethod;

public enum FmpApiList {

    /** ========== 관련 코드 조회(섹터 및 산업군 관련 조회) ========== **/

    AvailableExchanges (
            "Available Exchanges API",
            "https://financialmodelingprep.com/stable/available-exchanges",
            HttpMethod.GET,
            "https://site.financialmodelingprep.com/developer/docs/stable/available-exchanges",
            "거래소 목록 조회 API"
    ),


    AvailableSectors (
            "Available Sectors API",
            "https://financialmodelingprep.com/stable/available-sectors",
            HttpMethod.GET,
            "https://site.financialmodelingprep.com/developer/docs/stable/available-sectors",
            "사용가능한 섹터 목록 조회 API"
    ),

    AvailablesIndustries (
            "Available Industries API",
            "https://financialmodelingprep.com/stable/available-industries",
            HttpMethod.GET,
            "https://site.financialmodelingprep.com/developer/docs/stable/available-industries",
            "사용가능한 산업 목록 조회 API"
    ),

    AvailableCountries (
            "Available Countries API",
            "https://financialmodelingprep.com/stable/available-countries",
            HttpMethod.GET,
            "https://site.financialmodelingprep.com/developer/docs/stable/available-countries",
            "사용가능한 국가코드 목록 조회 API"
    ),



    /** ========== 거래소 및 기업 조회 ========== **/

    /** Symbol로 거래소심볼 조회 **/
    CompanyStockSymbolSearchBySymbol(
            "Stock Symbol Search API",
            "https://financialmodelingprep.com/stable/search-symbol",
            HttpMethod.GET,
            "https://site.financialmodelingprep.com/developer/docs/stable/search-symbol",
            "심볼로 거래소심볼 조회 API"
    ),

    /** 기업명으로 거래소심볼 조회 **/
    CompanyStockSymbolSearchByCompanyName(
            "Company Name Search API",
            "https://financialmodelingprep.com/stable/search-name",
            HttpMethod.GET,
            "https://site.financialmodelingprep.com/developer/docs/stable/search-name",
            "기업명으로 거래소심볼 조회 API"
    ),

    /** 기업 프로파일 조회 **/
    CompanyProfileData(
            "Company Profile Data API",
            "https://financialmodelingprep.com/stable/profile",
            HttpMethod.GET,
            "https://site.financialmodelingprep.com/developer/docs/stable/profile-symbol",
            "기업 프로파일 조회 API"
    ),


    /** ========== 재무제표 조회 ========== **/

    /** 영업이익 조회 **/
    IncomeStatement(
            "Income Statement Data API",
            "https://financialmodelingprep.com/stable/income-statement",
            HttpMethod.GET,
            "https://site.financialmodelingprep.com/developer/docs#income-statement",
            "영업이익 조회 API"
    ),

    /** 재무상태표 조회 **/
    BalanceSheetStatement(
            "Balance Sheet Statement Data API",
            "https://financialmodelingprep.com/stable/balance-sheet-statement",
            HttpMethod.GET,
            "https://site.financialmodelingprep.com/developer/docs#balance-sheet-statement",
            "재무상태표 조회 API"
    ),

    /** 기업가치 조회 **/
    EnterpriseValues(
            "Enterprise Values Data API",
            "https://financialmodelingprep.com/stable/enterprise-values",
            HttpMethod.GET,
            "https://site.financialmodelingprep.com/developer/docs#enterprise-values",
            "기업가치 조회 API"
    ),

    /** 재무비율지표 조회 **/
    FinancialRatios(
            "Financial Ratios Data API",
            "https://financialmodelingprep.com/stable/ratios",
            HttpMethod.GET,
            "https://site.financialmodelingprep.com/developer/docs#ratios",
            "재무비율지표 조회 API"
    ),

    /** 재무비율지표(TTM) 조회 **/
    FinancialRatiosTTM(
            "Financial Ratios Data API",
            "https://financialmodelingprep.com/stable/ratios-ttm",
            HttpMethod.GET,
            "https://site.financialmodelingprep.com/developer/docs#ratios-ttm",
            "재무비율지표(TTM) 조회 API"
    ),

    /** 영업이익 성장률 조회 **/
    IncomeStatementGrowth(
            "Income Statement Growth Data API",
            "https://financialmodelingprep.com/stable/income-statement-growth",
            HttpMethod.GET,
            "https://site.financialmodelingprep.com/developer/docs#income-statement-growth",
            "영업이익 성장률 조회 API"
    ),

    /** 성장률 조회 **/
    FinancialStatementsGrowth(
            "Financial Statement Growth API",
            "https://financialmodelingprep.com/stable/financial-growth",
            HttpMethod.GET,
            "https://site.financialmodelingprep.com/developer/docs#financial-growth",
            "성장률 조회 API"
    ),


    /** ========== 주식관련 데이터 조회 ========== **/

    /** 주식 스크리너 **/
    StockScreener(
            "Screener (Stock) API",
            "https://financialmodelingprep.com/api/v3/stock-screener",
            HttpMethod.GET,
            "https://site.financialmodelingprep.com/developer/docs/legacy/stock-screener-api",
            "주식 정보 조회 API"
    ),

    /** 최근 12개월 재무 비율 조회 **/
    RatiosTTM(
            "Ratios TTM API",
            "https://financialmodelingprep.com/api/v3/ratios-ttm/{symbol}",
            HttpMethod.GET,
            "https://site.financialmodelingprep.com/developer/docs/legacy/financial-ratio-free-api",
            "최근 12개월 재무 비율 조회 API"
    ),

    /** 외환 시세 조회(환율정보 조회) **/
    ForexQuote(
            "Forex Quote API",
            "https://financialmodelingprep.com/stable/quote",
            HttpMethod.GET,
            "https://site.financialmodelingprep.com/developer/docs#quote",
            "외환 시세 조회 API"
    ),

    /* 주식 시세 조회 */
    StockQuote(
            "Stock Quote API",
            "https://financialmodelingprep.com/stable/quote",
            HttpMethod.GET,
            "https://site.financialmodelingprep.com/developer/docs/stable/quote",
            "주식 시세 조회 API"
    ),

    /** 주식 시세 조회(간소화) **/
    StockQuoteShort(
            "Stock Quote Short API",
            "https://financialmodelingprep.com/stable/quote-short",
            HttpMethod.GET,
            "https://site.financialmodelingprep.com/developer/docs#quote-short",
            "주식 시세 조회 API(간소화 버전)"
    ),

    /** 애프터마켓 시세 조회 **/
    AftermarketTrade(
            "Aftermarket Trade API",
            "https://financialmodelingprep.com/stable/aftermarket-trade",
            HttpMethod.GET,
            "https://site.financialmodelingprep.com/developer/docs#aftermarket-trade",
            "애프터마켓 시세 조회 API"
    ),


    // -- 차트 조회

    /** 기업별 차트 조회 **/
    StockPriceandVolumeData(
            "Stock Price and Volume Data API",
            "https://financialmodelingprep.com/stable/historical-price-eod/full",
            HttpMethod.GET,
            "https://site.financialmodelingprep.com/developer/docs/stable/historical-price-eod-full",
            "주식 가격 그리고 거래량 데이터 조회 API"
    ),

    /** 애널리스트 추정치 조회 **/
    AnalystEstimates(
            "Analyst Estimates API",
            "https://financialmodelingprep.com/stable/analyst-estimates",
            HttpMethod.GET,
            "https://site.financialmodelingprep.com/developer/docs/stable/analyst-estimates",
            "애널리스트 추정치 조회 API (Forward EPS/매출 컨센서스)"
    ),

    /** 종목별 뉴스 조회 (stable API는 tickers 필터 미동작하여 v3 사용) **/
    StockNews(
            "Stock News API",
            "https://financialmodelingprep.com/api/v3/stock_news",
            HttpMethod.GET,
            "https://site.financialmodelingprep.com/developer/docs/stock-news-api",
            "종목별 최신 뉴스 조회 API"
    ),

    /** 거래소별 차트 조회 **/
    HistoricalIndexFullChart(
            "Historical Index Full Chart API",
            "https://financialmodelingprep.com/stable/historical-price-eod/full",
            HttpMethod.GET,
            "https://site.financialmodelingprep.com/developer/docs/stable/index-historical-price-eod-full",
            "기록 인덱스 전체 차트 조회 API"
    ),
    ;

    public final String name;
    public final String url;
    public final HttpMethod method;
    public final String apiDoc;
    public final String desc;


    FmpApiList(String name, String url, HttpMethod method, String apiDoc, String desc) {
        this.name = name;
        this.url = url;
        this.method = method;
        this.apiDoc = apiDoc;
        this.desc = desc;
    }

}
