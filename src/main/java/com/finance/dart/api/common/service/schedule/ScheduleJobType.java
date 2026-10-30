package com.finance.dart.api.common.service.schedule;

import lombok.Getter;

/**
 * 화면에서 관리하는 스케줄 작업 정의
 * - 작업을 추가하면 ManagedScheduleJob 구현 빈 + 여기 항목을 함께 추가
 */
@Getter
public enum ScheduleJobType {

    RECOMMENDED_STOCKS(
            "추천 종목 수집·전수 평가",
            "활성 추천 프로파일로 미국 종목을 스크리닝한 뒤 추천 종목 전체를 평가해 평가 이력에 저장 (약 4시간, FMP 사용)",
            "0 0 10 * * *"),   // 10시 - 미국장 종가 확정 후, 야간은 PC 꺼짐으로 누락되므로 낮 시간 유지
    KR_DAILY_PRICE(
            "국내 종목·일봉 수집",
            "KIS 종목 마스터로 국내 보통주 목록·업종(KSIC)을 갱신하고 전 거래일까지의 일봉(수정주가)·KOSPI/KOSDAQ 지수를 증분 수집 (첫 실행은 5년치 약 90분, 이후 약 7분, KIS 사용)",
            "0 0 16 * * MON-FRI"),   // 16시 - 전 거래일 확정 종가까지 저장(KrDailyPriceCollector.CLOSE_SETTLED_AT), 자동매매(10시)·미국 추천(10~14시)과 겹치지 않게
    KR_FINANCIAL(
            "국내 재무 수집",
            "OpenDart 정기보고서(분기·반기·사업)를 최근 공시 목록 기준으로 수집하고 5년 이력 중 빠진 보고서를 채움 (하루 최대 15,000건, 첫 적재 약 4일, OpenDart 사용)",
            "0 30 16 * * MON-FRI"),   // 16:30 - 국내 일봉 수집 뒤 (OpenDart라 KIS 한도와 무관)
    ;

    private final String label;
    private final String description;
    private final String defaultCron;

    ScheduleJobType(String label, String description, String defaultCron) {
        this.label = label;
        this.description = description;
        this.defaultCron = defaultCron;
    }
}
