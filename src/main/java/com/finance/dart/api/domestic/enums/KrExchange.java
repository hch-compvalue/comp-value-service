package com.finance.dart.api.domestic.enums;

import lombok.Getter;

/**
 * 국내 거래소 + KIS 종목 마스터 파일 형식
 * - 마스터 파일 한 줄 = [단축코드 9 + 표준코드 12 + 종목명(가변)] + [고정폭 뒷부분 tailLength 바이트]
 * - 뒷부분 필드 위치는 KIS 공식 파싱 예제(kis_kospi/kosdaq_code_mst)의 필드 폭 누적값 (2026-10-27 실파일로 확인)
 */
@Getter
public enum KrExchange {

    KOSPI("https://new.real.download.dws.co.kr/common/master/kospi_code.mst.zip", "0001",
            227, 29, 60, 61, 62, 105, 149, 158, 113),
    KOSDAQ("https://new.real.download.dws.co.kr/common/master/kosdaq_code.mst.zip", "1001",
            221, 24, 55, 56, 57, 100, 144, 153, 108),
    ;

    /** 뒷부분 공통 필드 위치 (그룹코드 2 + 시총규모 1 + 지수업종 대·중·소 각 4) */
    public static final int GROUP_OFFSET = 0;
    public static final int GROUP_LENGTH = 2;
    public static final int INDEX_LARGE_OFFSET = 3;
    public static final int INDEX_MID_OFFSET = 7;
    public static final int INDEX_SMALL_OFFSET = 11;
    public static final int INDEX_CODE_LENGTH = 4;
    public static final int DATE_LENGTH = 8;
    public static final int MONTH_LENGTH = 2;
    public static final int LISTED_SHARES_LENGTH = 15;
    /** 상장주식수는 천 주 단위 (삼성전자 5846278 → 실제 5,846,278,608주, 오차 0.01% 미만) */
    public static final long LISTED_SHARES_UNIT = 1_000L;

    private final String masterUrl;
    private final String indexCode;         // KIS 업종지수 코드 (종합지수)
    private final int tailLength;
    private final int spacOffset;
    private final int haltOffset;           // 거래정지
    private final int liquidationOffset;    // 정리매매
    private final int administrativeOffset; // 관리종목
    private final int listedDateOffset;
    private final int fiscalMonthOffset;
    private final int preferredOffset;      // 우선주 구분 (0 = 보통주)
    private final int listedSharesOffset;   // 상장주식수 (천 주)

    KrExchange(String masterUrl, String indexCode, int tailLength, int spacOffset, int haltOffset,
               int liquidationOffset, int administrativeOffset, int listedDateOffset,
               int fiscalMonthOffset, int preferredOffset, int listedSharesOffset) {
        this.masterUrl = masterUrl;
        this.indexCode = indexCode;
        this.tailLength = tailLength;
        this.spacOffset = spacOffset;
        this.haltOffset = haltOffset;
        this.liquidationOffset = liquidationOffset;
        this.administrativeOffset = administrativeOffset;
        this.listedDateOffset = listedDateOffset;
        this.fiscalMonthOffset = fiscalMonthOffset;
        this.preferredOffset = preferredOffset;
        this.listedSharesOffset = listedSharesOffset;
    }
}
