package com.finance.dart.api.common.dto.evaluation;

import com.finance.dart.api.abroad.dto.fmp.chart.StockPriceVolumeResDto;
import com.finance.dart.api.abroad.dto.fmp.quote.StockQuoteResDto;
import com.finance.dart.api.common.dto.CompanySharePriceResult;

import java.util.List;

/**
 * 6단계 평가 입력 — 시장과 무관한 점수 계산(StockEvaluationService.evaluate)에 넘기는 묶음
 * - 미국: FMP(프로필·시세·1년 가격·애널리스트 추정치)로 채움 / 국내: 우리 DB(일봉·재무)로 채움 (ROADMAP_domestic 4단계)
 *
 * @param sector,industry,exchange,country 표시용 (없으면 "N/A")
 * @param beta              없으면 null
 * @param marketCap         품질 게이트(초소형주) 판단용, 시장 통화 단위 (없으면 null)
 * @param marketCapText     표시용 (예: "$1.2B", "1.2조원")
 * @param stockQuote        현재가·SMA50·SMA200·거래량 (없으면 null)
 * @param priceHistory      최근 1년 일봉, 최신순 (없으면 null)
 * @param forwardEps        애널리스트 추정 EPS (없으면 null → Forward PER 크로스체크 생략)
 * @param microcapThreshold 이 시총 미만 매수후보는 관심목록으로 (시장 통화 단위)
 * @param holdingCompany    지주회사 — 적정가 신뢰 낮음, 매수후보는 관심목록으로 (국내)
 */
public record EvaluationInputs(CompanySharePriceResult result, String sector, String industry, Double beta,
                               String exchange, String country, Long marketCap, String marketCapText, Long averageVolume,
                               StockQuoteResDto stockQuote, List<StockPriceVolumeResDto> priceHistory, Double forwardEps,
                               long microcapThreshold, boolean holdingCompany) {}
