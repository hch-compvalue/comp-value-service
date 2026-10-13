package com.finance.dart.api.common.dto;

import lombok.Data;

/**
 * 1주당 가치 계산 결과
 */
@Data
public class CompanySharePriceResultDetail {

    private String 단위 = "N/A";

    private String 영업이익_전전기 = "N/A";
    private String 영업이익_전기 = "N/A";
    private String 영업이익_당기 = "N/A";
    private String 영업이익_합계 = "N/A";
    private String 영업이익_평균 = "N/A";

    private String 유동자산합계 = "N/A";

    private String 유동부채합계 = "N/A";

    private String 유동비율 = "N/A";

    private String 투자자산_비유동자산내 = "N/A";

    private String 고정부채 = "N/A";

    private String 발행주식수 = "N/A";


    /** V2 추가 **/
    private String PER = "N/A";
    private String PEG = "N/A";
    private String EPS성장률 = "N/A";
    private String 영업이익성장률 = "N/A";
    private String 성장률보정PER = "N/A";
    private String 무형자산 = "N/A";

    private String 연구개발비_당기 ="N/A";
    private String 연구개발비_전기 = "N/A";
    private String 연구개발비_전전기 = "N/A";
    private String 연구개발비_평균 = "N/A";

    private String 총부채 = "N/A";
    private String 현금성자산 = "N/A";
    private String 순부채 = "N/A";

    /** 계산값 **/
    private String 계산_사업가치 = "N/A";
    private String 계산_재산가치 = "N/A";
    private String 계산_부채 = "N/A";
    private String 계산_기업가치 = "N/A";

    /** 기타값 **/
    private String 예외메세지_영업이익 = "N/A";
    private String 예외메시지_발행주식수 = "N/A";

    /** V3 추가 **/
    // PER 이 100 이상일 시 수익가치 평가 불가능한 기업으로 판단 (적자 직전, 실적 급감 구간, 초기 성장주)
    private boolean 수익가치계산불가 = false;
    private boolean 적자기업 = false;
    private boolean 매출기반평가 = false; // PER 이 마이너스거나 100이상인데 매출액 성장률이 높으면 매출기반으로 평가
    private boolean 흑자전환기업 = false; // 전기 적자에서 당기 흑자로 전환된 기업
    private String 매출액 = "N/A";
    private String 매출성장률 = "N/A";
    private String 매출성장률보정계수 = "N/A";
    private String PSR = "N/A";


    /** V6 추가: PER 블렌딩 **/
    private String 실제PER = "N/A";
    private String 섹터PER = "N/A";
    private String 블렌딩PER = "N/A";

    /** V6 추가: 분기 추세 **/
    private String 분기영업이익_Q1 = "N/A";
    private String 분기영업이익_Q2 = "N/A";
    private String 분기영업이익_Q3 = "N/A";
    private String 분기영업이익_Q4 = "N/A";
    private String 분기추세팩터 = "N/A";
    private boolean 분기실적악화 = false;

    /** V6 추가: 연간 영업이익 추세 **/
    private String 영업이익추세팩터 = "N/A";
    private boolean 연속하락추세 = false;
    private boolean 단일하락추세 = false;
    private boolean 연속상승추세 = false;

    /** V7 추가 **/
    private boolean 급락종목할인 = false;
    private boolean 분기적자전환 = false;
    private String PBR = "N/A";

    /** V7 그레이엄 스크리닝 **/
    private boolean 그레이엄_PER통과 = false;
    private boolean 그레이엄_PBR통과 = false;
    private boolean 그레이엄_복합통과 = false;   // PER×PBR 체크
    private boolean 그레이엄_유동비율통과 = false;
    private boolean 그레이엄_연속흑자통과 = false;
    private int 그레이엄_통과수 = 0;            // 0~5
    private String 그레이엄_등급 = "N/A";       // "강력매수"/"매수"/"관망"/"위험"

    /** V8 모멘텀/기술적 분석 **/
    private String SMA50 = "N/A";
    private String SMA200 = "N/A";
    private String RSI = "N/A";
    private String 거래량비율 = "N/A";        // 최근10일평균/이전10일평균
    private int 모멘텀_MA점수 = 0;             // 0~6
    private int 모멘텀_RSI점수 = 0;            // 0~5
    private int 모멘텀_거래량점수 = 0;          // 0~4
    private boolean 모멘텀게이트통과 = true;    // 하드 게이트 통과 여부
    private String 모멘텀게이트사유 = "N/A";

    /** V8 동적 안전마진 **/
    private String 안전마진율 = "N/A";          // 실제 적용된 안전마진 비율

    /** V8 과대평가 의심 할인 **/
    private boolean 과대평가의심할인 = false;

    /** V8 PBR 기반 평가 (Financial Services) **/
    private boolean PBR기반평가 = false;
    private String BPS = "N/A";
    private String ROE = "N/A";
    private String COE = "N/A";
    private String targetPBR = "N/A";
    private String 실제PBR = "N/A";

    /** V9 자기자본법 **/
    private String 적용세율 = "N/A";            // 세후 이익 산출에 쓴 세율 (유효세율 or 기본값)
    private String 이자비용 = "N/A";            // 영업이익에서 차감한 이자비용 = max(실제 이자, 총부채×정상금리 6%)
    private String 세후수익가치 = "N/A";         // (영업이익-이자)×(1-세율)×PER×분기추세

    public CompanySharePriceResultDetail(String unit) {
        this.단위 = unit;
    }

}
