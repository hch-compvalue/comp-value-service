package com.finance.dart.api.domestic.valuation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 국내 업종(KIS 표준산업분류 코드) → V9 섹터 파라미터 이름(미국 FMP 섹터명) + 금융 세부 업종
 * - 초기값은 미국 섹터 파라미터를 그대로 쓴다 (ROADMAP_domestic 3단계 — 국내 전용 파라미터는 6단계 검증 후)
 * - KIS ksic_code 형식 = 앞 2자리 대분류 번호 + [KSIC 중분류 2자리 + 소분류 순번 2자리]
 *   (예: 032604 → 2604 = KSIC 264 통신·방송장비, 116401 → 6401 = 641 은행, 116409 → 6409 = 649 기타 금융업)
 *   → 4자리 예외(소분류) → 2자리 중분류 순으로 찾는다 (11-02 실데이터로 형식 확인: 은행이 641x 가 아니라 6401)
 * - 금융 판정 = KSIC 금융업(64~66) **이면서** 재무상태표에 유동/비유동 구분이 없을 때만
 *   (KSIC 649 '기타 금융업'에 LG·SK·CJ·GS 등 비금융 지주 70곳이 섞여 있음 — 금융회사는 유동 구분 없는 재무상태표를 쓴다.
 *    11-02 확인: 금융업 코드 회사 중 유동 구분 없음 = 은행·보험·증권·카드·캐피탈·금융지주, 있음 = 비금융 지주·NHN 등)
 *   유동 구분이 있으면 지주·기타로 보고 Industrials
 * - 금융 세부: V9 금융 경로(PBR)가 industry 문자열의 bank / insurance 로 ROE 상한을 나눈다
 *   → 은행 6401, 보험 65xx, 그 외 Capital Markets. 은행지주는 KSIC가 기타 금융업이라 종목코드로 은행 지정
 * - 매핑 안 되는 업종은 sector = null (호출부가 경고를 남기고 기본 파라미터 사용)
 */
public final class KrSectorMapper {

    public static final String FINANCIAL = "Financial Services";
    static final String INDUSTRIALS = "Industrials";
    static final String INDUSTRY_BANK = "Banks";
    static final String INDUSTRY_INSURANCE = "Insurance";
    static final String INDUSTRY_CAPITAL_MARKETS = "Capital Markets";
    private static final String KSIC_BANK = "6401";
    private static final String KSIC_INSURANCE_DIVISION = "65";
    private static final int KSIC_PREFIX_LENGTH = 2;
    private static final int KSIC_CLASS_LENGTH = 4;
    private static final int KSIC_DIVISION_LENGTH = 2;

    /** 은행지주·특수은행 (KSIC가 기타 금융업이라 은행 코드로 못 잡는 은행) */
    static final Set<String> BANK_STOCK_CODES = Set.of(
            "105560",   // KB금융
            "055550",   // 신한지주
            "086790",   // 하나금융지주
            "316140",   // 우리금융지주
            "138930",   // BNK금융지주
            "175330",   // JB금융지주
            "139130",   // iM금융지주
            "024110",   // 기업은행
            "323410");  // 카카오뱅크

    /** KSIC 금융업 중분류 */
    static final Set<String> FINANCIAL_DIVISIONS = Set.of("64", "65", "66");

    /** KSIC 코드 → 섹터 (4자리 소분류 예외 → 2자리 중분류) */
    static final Map<String, String> PREFIX_SECTOR = new LinkedHashMap<>();
    static {
        // 4자리 소분류 예외
        PREFIX_SECTOR.put("2701", "Healthcare");              // 의료용 기기 (KSIC 271)
        PREFIX_SECTOR.put("5801", "Communication Services");  // 서적·신문·잡지 출판 (581)
        PREFIX_SECTOR.put("5802", "Technology");              // 소프트웨어 개발·공급 (582, 게임 포함 — 소분류로 못 나눔)
        PREFIX_SECTOR.put("4702", "Consumer Defensive");      // 음·식료품 소매 (472)
        // 2자리 중분류
        put("Consumer Defensive", "01", "02", "03", "10", "11", "12", "85");
        put("Energy", "05", "06", "19");
        put("Basic Materials", "07", "08", "16", "17", "20", "22", "23", "24");
        put("Consumer Cyclical", "13", "14", "15", "30", "32", "33", "45", "46", "47", "55", "56", "75", "91", "95", "96");
        put(INDUSTRIALS, "18", "25", "28", "29", "31", "34", "38", "39", "41", "42", "49", "50", "51", "52", "71", "72", "73", "74", "76");
        put("Healthcare", "21", "70", "86", "87");
        put("Technology", "26", "27", "62");                 // 27: 측정·제어·광학 (의료기기 2701 은 위 예외)
        put("Utilities", "35", "36", "37");
        put("Communication Services", "58", "59", "60", "61", "63", "90");   // 63: 포털(NAVER·카카오) 포함
        put(FINANCIAL, "64", "65", "66");
        put("Real Estate", "68");
    }

    /** 매핑 결과 (sector null = 매핑 안 됨) */
    public record KrSector(String sector, String industry, String ksicClass) {}

    private KrSectorMapper() {}

    /**
     * @param classifiedBalanceSheet 최근 재무상태표에 유동/비유동 구분이 있는가 (유동자산 값 존재)
     */
    public static KrSector map(String stockCode, String ksicCode, boolean classifiedBalanceSheet) {
        String cls = ksicClass(ksicCode);
        if (BANK_STOCK_CODES.contains(stockCode)) return new KrSector(FINANCIAL, INDUSTRY_BANK, cls);
        if (cls == null) return new KrSector(null, null, null);
        String division = cls.substring(0, KSIC_DIVISION_LENGTH);
        if (FINANCIAL_DIVISIONS.contains(division)) {
            return classifiedBalanceSheet ? new KrSector(INDUSTRIALS, null, cls) : new KrSector(FINANCIAL, financialIndustry(cls), cls);
        }
        String sector = PREFIX_SECTOR.getOrDefault(cls, PREFIX_SECTOR.get(division));
        return new KrSector(sector, null, cls);
    }

    static String financialIndustry(String cls) {
        if (cls.equals(KSIC_BANK)) return INDUSTRY_BANK;
        if (cls.startsWith(KSIC_INSURANCE_DIVISION)) return INDUSTRY_INSURANCE;
        return INDUSTRY_CAPITAL_MARKETS;
    }

    /** "116409" → "6409" (앞 2자리 대분류 번호 제거) */
    static String ksicClass(String ksicCode) {
        if (ksicCode == null || ksicCode.length() != KSIC_PREFIX_LENGTH + KSIC_CLASS_LENGTH) return null;
        String cls = ksicCode.substring(KSIC_PREFIX_LENGTH);
        return cls.chars().allMatch(Character::isDigit) ? cls : null;
    }

    private static void put(String sector, String... divisions) {
        for (String d : divisions) PREFIX_SECTOR.put(d, sector);
    }
}
