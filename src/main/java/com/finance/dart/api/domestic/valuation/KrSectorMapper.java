package com.finance.dart.api.domestic.valuation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 국내 업종(표준산업분류 KSIC) → V9 섹터 파라미터 이름(미국 FMP 섹터명) + 금융 세부 업종
 * - 초기값은 미국 섹터 파라미터를 그대로 쓴다 (ROADMAP_domestic 3단계 — 국내 전용 파라미터는 6단계 검증 후)
 * - KIS ksic_code = 앞 2자리 대분류 번호 + KSIC 4자리 세세분류 앞부분 (예: 116409 → 6409 기타 금융업, 032604 → 2604 통신·방송장비)
 * - 가장 긴 접두어가 우선 (4자리 예외 → 3자리 → 2자리 중분류)
 * - 금융: V9 금융 경로(PBR)가 industry 문자열에 bank / insurance 가 들어 있는지로 ROE 상한을 나눈다
 *   → 은행 641x, 보험 65xx, 그 외 금융은 Capital Markets. 은행지주는 KSIC가 '기타 금융업(6409)'이라 종목코드로 은행 지정
 * - 매핑 안 되는 업종은 sector = null (호출부가 경고를 남기고 기본 파라미터 사용)
 */
public final class KrSectorMapper {

    public static final String FINANCIAL = "Financial Services";
    static final String INDUSTRY_BANK = "Banks";
    static final String INDUSTRY_INSURANCE = "Insurance";
    static final String INDUSTRY_CAPITAL_MARKETS = "Capital Markets";
    private static final int KSIC_PREFIX_LENGTH = 2;
    private static final int KSIC_CLASS_LENGTH = 4;

    /** 은행지주·특수은행 (KSIC가 기타 금융업·지주 등으로 분류돼 접두어로 못 잡는 은행) */
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

    /** KSIC 접두어 → 섹터 (긴 접두어 우선) */
    static final Map<String, String> PREFIX_SECTOR = new LinkedHashMap<>();
    static {
        // 4자리 예외
        PREFIX_SECTOR.put("2042", "Consumer Defensive");      // 세제·화장품
        PREFIX_SECTOR.put("5821", "Communication Services");  // 게임 소프트웨어
        PREFIX_SECTOR.put("6312", "Communication Services");  // 포털·인터넷 정보매개
        PREFIX_SECTOR.put("7150", "Industrials");             // 비금융 지주회사
        // 3자리 예외
        PREFIX_SECTOR.put("271", "Healthcare");               // 의료용 기기
        PREFIX_SECTOR.put("582", "Technology");               // 소프트웨어 개발·공급
        PREFIX_SECTOR.put("581", "Communication Services");   // 서적·신문·잡지 출판
        PREFIX_SECTOR.put("472", "Consumer Defensive");       // 음·식료품 소매
        PREFIX_SECTOR.put("631", "Technology");               // 자료처리·호스팅 (6312 제외)
        // 2자리 중분류
        put("Consumer Defensive", "01", "02", "03", "10", "11", "12", "85");
        put("Energy", "05", "06", "19");
        put("Basic Materials", "07", "08", "16", "17", "20", "22", "23", "24");
        put("Consumer Cyclical", "13", "14", "15", "30", "32", "33", "45", "46", "47", "55", "56", "75", "91", "95", "96");
        put("Industrials", "18", "25", "28", "29", "31", "34", "38", "39", "41", "42", "49", "50", "51", "52", "71", "72", "73", "74", "76");
        put("Healthcare", "21", "70", "86", "87");
        put("Technology", "26", "27", "62");   // 27: 측정·제어·광학 (의료기기 271 은 위 예외)
        put("Utilities", "35", "36", "37");
        put("Communication Services", "58", "59", "60", "61", "63", "90");
        put(FINANCIAL, "64", "65", "66");
        put("Real Estate", "68");
    }

    /** 매핑 결과 (sector null = 매핑 안 됨) */
    public record KrSector(String sector, String industry, String ksicClass) {}

    private KrSectorMapper() {}

    public static KrSector map(String stockCode, String ksicCode) {
        if (BANK_STOCK_CODES.contains(stockCode)) return new KrSector(FINANCIAL, INDUSTRY_BANK, ksicClass(ksicCode));
        String cls = ksicClass(ksicCode);
        if (cls == null) return new KrSector(null, null, null);
        String sector = null;
        for (int len = cls.length(); len >= KSIC_PREFIX_LENGTH && sector == null; len--) {
            sector = PREFIX_SECTOR.get(cls.substring(0, len));
        }
        return new KrSector(sector, FINANCIAL.equals(sector) ? financialIndustry(cls) : null, cls);
    }

    static String financialIndustry(String cls) {
        if (cls.startsWith("641")) return INDUSTRY_BANK;
        if (cls.startsWith("65")) return INDUSTRY_INSURANCE;
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
