package com.finance.dart.api.common.constants;

/**
 * 시장 구분 (국내주식 로드맵 1단계, docs/ROADMAP_domestic.md)
 * - US: 미국주식 (FMP)
 * - KR: 국내주식 (KIS + OpenDart)
 *
 * <p>DB 컬럼 market 의 값. 기존 행은 모두 US(컬럼 기본값).
 * symbol 유니크 제약에는 market 을 넣지 않음 — 국내 코드(6자리 숫자)와 미국 티커(영문)는 겹치지 않는다.</p>
 */
public enum Market {
    US,
    KR;

    /** DB 컬럼 정의 — 기존 행을 US 로 채우며 추가 (ddl-auto: update) */
    public static final String COLUMN_DEFINITION = "varchar(2) default 'US' not null";
}
