# CompValue Backend - 주당가치 계산 고도화 이력

## 현재 운영 버전: V8

`EvaluationConst.CAL_VALUE_VERSION = "v8"`

---

## 버전별 고도화 이력

### V1 - 기본 적정가 계산
- **공식**: `영업이익평균 × 고정PER(10) + 재산가치 - 부채` ÷ 발행주식수
- 재산가치 = 유동자산 - (유동부채 × 유동비율) + 투자자산
- 고정 PER 10 사용, 성장률 미반영
- 파일: `PerShareValueCalculationService.calPerValue()`

### V2 - 성장률 반영 PER 도입
- **adjustedPER** = PER × (1 + 영업이익성장률)
- PEG 비율 도입: PER / EPS성장률
- R&D 3년 평균 계산 추가
- 순부채(총부채 - 현금) 차감 도입
- 무형자산 가중치 0.3 (고정)
- **추가 필드**: PER, PEG, EPS성장률, 영업이익성장률, R&D(3년), 순부채

### V3 - 적자/고성장 기업 PSR 경로
- PER ≤ 0 또는 > 100이면 수익가치 계산불가 판정
- **PSR 경로**: 매출액 × PSR × 매출성장률보정계수 (매출성장률 > 20% 시)
- 매출성장률보정계수: 20~30%→0.3, 30~50%→0.5, 50~80%→0.7, 80%+→0.9
- 흑자전환기업 플래그 추가
- adjustedPER 상한: 실제PER × 2.5
- **추가 필드**: 수익가치계산불가, 적자기업, 매출기반평가, 흑자전환기업, 매출액, PSR

### V4 - 섹터별 파라미터 도입
- `SectorParameterFactory` + `SectorCalculationParameters` 신설
- 11개 섹터별 차등 파라미터: basePER, 무형자산가중치, maxPSR, 성장률상한, R&D가중치
- 섹터별 유동비율 적용 여부 (금융업 미적용)
- **해결**: 테크/바이오 vs 유틸리티 등 섹터 특성 무시 문제

### V5 - (예약, V4와 동일)
- `calPerValueV5()` → 내부적으로 V4 호출

### V6 - PER 블렌딩 + 추세팩터
- **PER 블렌딩**: `실제PER×0.6 + 섹터PER×0.4`
- **가중평균 영업이익**: 전전기×1 + 전기×2 + 당기×3 (÷6)
- **연간 추세팩터**: 연속하락 0.8, 단일하락 0.9
- **분기 추세팩터**: Q1<Q2이고 최근반기<이전반기 → 0.7, Q1<Q2만 → 0.85
- adjustedPER 상한: 섹터PER × 2.5
- **추가 필드**: 블렌딩PER, 분기영업이익(Q1~Q4), 분기추세팩터, 영업이익추세팩터

### V7 - 흑자전환 보수화 + 그레이엄 스크리닝
- 흑자전환기업 특별처리: PER 블렌딩 시 섹터 평균 쪽으로 보수적 블렌딩
- **분기 적자전환 감지**: Q1 < 0 && Q2 > 0 → 추세팩터 × 0.5
- adjustedPER 상한 강화: 섹터PER × 1.8 (V6의 2.5에서 축소)
- **그레이엄 스크리닝 5항목**: PER통과, PBR통과, PER×PBR통과, 유동비율통과, 연속흑자통과
- 그레이엄 등급: 강력매수(5/5), 매수(4/5), 관망(3/5), 위험(0~2/5)
- **추가 필드**: 그레이엄 관련 7개, PBR, 급락종목할인

### V8 - 보수적 PER 블렌딩 + PBR 금융주 + 모멘텀 게이트 + 6단계 점수체계
**핵심 변경:**

1. **PER 블렌딩 보수화**
   - `cappedPER = min(실제PER, 섹터PER×1.5)` → 이후 블렌딩
   - adjustedPER 상한: 섹터PER × 2.0 (V7의 1.8에서 조정)

2. **고성장률 지속가능성 할인**
   - 성장률 50% 초과분은 50%만 인정
   - 예: 80% → 50% + (30%×0.5) = 65%

3. ~~**52주 최고가 캡**~~ (제거됨 - 순수 내재가치 기반으로 전환)
   - 내재가치는 펀더멘털로만 산출, 시장가격은 매수/매도 판단 시점에서만 비교

4. **동적 안전마진** (25~45% 범위)
   - 기본 30% + Beta 조정 + 그레이엄 통과수 조정

5. **매매가 산출**
   - 매수적정가 = 적정가 × (1 - 안전마진)
   - 목표매도가 = 적정가 × 0.95
   - 손절매가 = 매수적정가 × 0.8

6. **모멘텀/기술적 분석** (게이트 제거됨)
   - SMA50/SMA200, RSI, 거래량 분석 → Step6 점수(0~18점)로 자연 반영
   - 데스크로스 등 약세 신호는 Step6 저점수로 충분히 감점 (총점 상한 강제 적용 없음)

7. **6단계 점수체계** (총 100점)
   - Step1 위험신호(12점), Step2 신뢰도(18점), Step3 밸류에이션(20점)
   - Step4 영업이익추세(15점), Step5 투자적합성(17점), Step6 모멘텀(18점)
   - **등급 기준**: S(92+), A(83+), B(73+), C(63+), D(50+), F(<50)
   - **Step5 구성**: 매수적정가(9점, 세분화) + 그레이엄(8점, 강화). PEG/PSR/PBR 이진 판단은 Step3과 이중 계산 해소를 위해 제거됨

8. **PBR 기반 금융주 평가** (Financial Services 전용)
   - COE = 무위험수익률(4.5%) + Beta × 시장위험프리미엄(5.5%) ← 보수화
   - ROE cap: 서브섹터별 (은행 18%, 보험 20%, 기타 25%) ← 레버리지 부풀림 방지
   - targetPBR = cappedROE / COE, 서브섹터별 상한 (은행 1.8, 보험 2.0, 기타 2.5)
   - 적정가 = BPS × targetPBR × 추세팩터 ← 순부채 차감 제거 (BPS가 이미 순자산)
   - industry 기반 서브섹터 분류: Bank*, Insurance*, 그 외(Capital Markets, Credit 등)
   - BPS 없으면 기존 PER 기반으로 자동 폴백
   - Step3 PBR/targetPBR 임계값 강화 (은행 저PBR은 업계 정상이므로 기준 엄격화: 0.3/0.5/0.7/0.85/1.0/1.2)
   - Step5에서는 PBR 이진 판단 제거 (Step3에서 이미 평가)

9. **과대평가 의심 할인**
   - Step5에서 적정가 대비 고평가 비율에 따라 그레이엄 점수 단계적 감점

10. **극단값 방지 안전장치**
    - PSR 경로 STEP01 상한: 시가총액 × 3 초과 방지 (적자 고매출 기업의 적정가 폭주 방지)
    - 추세팩터 복합 하한 0.5: 연간×분기 추세팩터 곱이 0.5 미만이 되지 않도록 보호
    - Step3 성장률: 적정가에서 이미 50%초과분 할인 적용하므로 점수에서 이중 감점 없음
    - Step4 당기급증: 별도 의심 분기 없이 일반 추세 로직으로 평가

- **추가 필드**: SMA50/200, RSI, 거래량비율, 모멘텀점수, 안전마진율, 과대평가의심할인, PBR기반평가, BPS, ROE, COE, targetPBR, industry

11. **진입 타이밍 → 등급 상한 연동** (2026-03-17)
    - 기존 analyzeEntryTiming()이 "표시만" → 등급에 연동
    - 타이밍 점수 < 30 ("하락 구간") → 총점 상한 65점, < 50 ("대기 권장") → 상한 77점
    - 하락 추세 중 A등급 매수 추천 방지

12. **SMA200 하방 디스카운트** (2026-03-17)
    - Step6에서 현재가/SMA200 비율에 따라 추가 감점 (-1/3/5점)
    - 중장기 하락 추세 반영 강화

13. **52주 고점 대비 하락률 감점** (2026-03-17)
    - 52주 고점 대비 -20% 이상: -2점, -30% 이상: -4점
    - "떨어져서 싸 보이는" 가치 함정 방지

14. **Forward PER 크로스체크** (2026-03-17)
    - FMP analyst-estimates API 추가 (종목당 12호출)
    - Forward PER / TTM PER >= 1.3: -3점, >= 1.5: -5점
    - 시장이 예상하는 미래 실적 악화 반영

15. **가치×타이밍 2축 분리** (2026-08-11)
    - **배경**: 게이트(타이밍/SMA200/52주하락)가 단일 총점을 눌러 펀더멘털 우수 종목도 C등급으로 뭉개짐. 가치 스크리닝(하락 종목)과 타이밍 게이트가 서로 모순 → 추천 0건/신뢰 저하
    - **가치등급**: Step1~5 펀더멘털 합(최대 82점)을 100점 환산 → 기존 등급 임계값 재사용. **게이트/타이밍 감점 미반영** (4중 감점 문제를 가치축에서 해소)
    - **타이밍신호**: 기존 `entryTiming.signal`을 양호/대기/하락/관망으로 표준화 (매수적기→양호, 대기권장→대기, 하락구간→하락). null이면 step6 점수로 폴백
    - **투자판정 매트릭스**: 가치 S/A/B + 타이밍 양호 → "매수 후보", 가치 S/A/B + 타이밍 미흡 → "관심목록", 가치 C/D/F → "관망"
    - **하위호환**: 기존 totalScore/grade/recommendation(게이트 적용본) 그대로 유지, 프론트 화면에서만 숨김(엑셀/CSV 레거시 컬럼 유지)
    - **파일**: `StockEvaluationService`(정적 헬퍼 calculateValueScore/resolveTimingSignal/resolveInvestmentSignal/resolveInvestmentSignalColor), `EvaluationConst`(VALUE_SCORE_MAX, INVEST_SIGNAL_*, TIMING_* 상수), `StockEvaluationResponse`(investmentSignal/valueScore/valueGrade/timingSignal/timingScore 필드), 프론트 `InvestmentEvaluation.jsx`
    - **추가 필드**: investmentSignal, investmentSignalColor, valueScore, valueGrade, timingSignal, timingScore

16. **매수후보 품질 게이트 + 채권필터 강화 + 극단괴리 신뢰도 하향** (2026-08-13)
    - **배경**: 실전 CSV(저평가성장주 302종목) 분석 결과 매수후보 26개 중 clean은 6개뿐 — 초소형주/외국ADR/극단괴리/채권이 매수후보로 샘. 밸류에이션 계산 약점(소형주 BPS, 금융주, ETD채권)이 그대로 노출
    - **Fix1 품질게이트** `StockEvaluationService.applyQualityGate()`: 채권 or 이상치괴리(±2000%)→관망, 매수후보+초소형주(<$300M)/극단괴리(±200%)→관심목록. investmentSignal 산정 직후 적용
    - **Fix2 채권감지 강화** `SecurityTypeUtil`(신설, RecommendedStocksProcessor 로직 이전+공용화): '1M BD'(1st mortgage bond) 이름토큰 + Entergy ETD 심볼블록리스트(ENJ/ENO/EMP). 추천필터+평가게이트 공용
    - **Fix3 극단괴리 가치점수 하향** `evaluateStep3`: 가격차이율 절대값 ≥200%면 저평가 만점(+6) 대신 +1(계산신뢰도 낮음). 소형주/금융주 계산오류가 가치등급 부풀리는 것 방지
    - **효과**(시뮬): 매수후보 26→14, WHLR/MASK/ENJ 등 제거. 상수: QUALITY_GATE_MICROCAP/UNREALISTIC_GAP_PCT/OUTLIER_GAP_PCT
    - 미적용(범위밖): 중국ADR 매수후보 강등(ATAT 등 잔존)

- **추가 필드**: high52wDropPercent, forwardPer, forwardPerWarning

17. **백테스트 기반 즉시 버그픽스 5건** (2026-10-13)
    - **배경**: FMP 과거 데이터로 운영 V8을 as-of 재실행한 백테스트(530종목×2018~2025 31시점, 하네스 `test/backtest-v8-2026-10/`)에서 설계와 다르게 동작하는 버그 확인
    - **PBR 경로 미작동 수정**: `getCalParamDataV8`이 `result.get섹터()`(계산 후에야 세팅)로 판단해 2026-03 이후 금융주 전부 PER 경로였음 → 프로필 섹터를 인자로 전달. BPS(ratios-TTM) 환율 미변환 문제로 **USD 공시 기업만** PBR 경로, 그 외는 PER 경로 유지
    - **그레이엄→안전마진 순서**: 스크리닝이 안전마진 계산 뒤에 있어 통과수가 항상 0(+5% 고정) → `applyGrahamScreeningV8()` 추출, 안전마진 전에 호출
    - **주당가치 소수 2자리**: `CalUtil.divide`(3인자) 정수 반올림 → `divideToPerShare()`(scale 2, 주식수 없음/0이면 "0" 유지). 레거시가 쓰는 CalUtil은 미변경
    - **수익가치(STEP01) 하한 0**: 음수 영업이익×PER이 자산가치를 깎고, 음수에 추세할인(<1) 곱해 실적 악화 시 적정가가 오르던 역전 제거. 적정가 ≤0 자체는 기존 평가 게이트(총점 상한 50) 유지
    - **추천 프로파일 적자·자본잠식 배제** (`RecommendedStocksProcessor.isUndervalued`): PER/PBR 필터 설정 시 값 ≤0 제외(하한을 음수로 명시하면 허용), 자본잠식(PBR<0)이면 ROE/부채비율 필터 불통과. 10-13 고수익 저평가 551건 중 PER≤0 249건·PBR<0 202건이 통과하던 문제
    - **효과(백테스트)**: 적정가≤0 16.4→10.8%, 괴리>200% 18.2→6.5%, 금융 괴리 중앙값 +410→-24%, 12m IC 0.083(t2.7)→0.058(t3.4), 24m 0.126(t3.9)→0.093(t4.4). 비금융 정상행 7,329건은 반올림 외 동일(회귀 없음)
    - **미적용(V9 후보)**: 현금 이중가산(STEP02 유동자산+STEP05 순부채), 리스부채 이중차감(FMP totalDebt에 리스 포함), EBIT×순이익PER(+27%), 운전자본·영업권 중복가산

---

## 계산 구조 (전 버전 공통 골격)

```
STEP01 = 영업이익가중평균 × adjustedPER × 추세팩터들
STEP02 = 유동자산 - (유동부채 × K) + 투자자산       ← K: 유동비율 기반 차감계수
STEP03 = 무형자산 × 섹터가중치
STEP05 = 순부채 (총부채 - 현금)

기업가치 = STEP01 + STEP02 + STEP03 - STEP05
주당가치 = 기업가치 ÷ 발행주식수          ← V8: 소수 2자리, STEP01 하한 0
```

V8 Financial Services (PBR 경로):
```
COE = 4.5% + Beta × 5.5%
cappedROE = min(ROE, 서브섹터별cap)   ← 은행18%, 보험20%, 기타25%
targetPBR = cappedROE / COE           ← 은행≤1.8, 보험≤2.0, 기타≤2.5
주당가치 = BPS × targetPBR × 추세팩터  ← 순부채 차감 없음 (BPS=순자산)
```

V8 후처리:
```
→ 동적 안전마진 → 매매가 산출 (순수 내재가치 기반)
```

---

## 주요 파일

| 파일 | 역할 |
|------|------|
| `PerShareValueCalculationService.java` | V1 + V8 계산 로직 |
| `PerShareValueCalcLegacyService.java` | V2~V7 레거시 계산 로직 |
| `PerShareValueCalcHelper.java` | 공통 헬퍼 (PEG, 가중평균, 안전마진, 매매가) |
| `US_StockCalFromFpmService.java` | FMP 데이터 수집 + 계산 오케스트레이션 |
| `StockEvaluationService.java` | 6단계 점수 평가 |
| `SectorParameterFactory.java` | 섹터별 파라미터 팩토리 |
| `SectorCalculationParameters.java` | 섹터 파라미터 DTO |
| `CompanySharePriceCalculator.java` | 계산 입력 DTO |
| `CompanySharePriceResultDetail.java` | 계산 결과 상세 DTO |
| `EvaluationConst.java` | 평가 상수 (가중치, 기준값) |

---

## 변경 시 주의사항

- 새 버전 추가 시 반드시 독립 메서드 세트 생성 (버전 간 메서드 호출 금지)
- FMP API 종목당 12회 이내 유지 (rate limit: 분당 300건)
- `CompanySharePriceResultDetail`에 필드 추가 시 기본값 설정 필수 (Redis 역직렬화 호환)
- 섹터 파라미터 변경 시 전체 테스트 regression 확인

---

## 버전 관리 규칙

- **각 버전(V6, V7, V8)은 완전히 독립** — 다른 버전 메서드 호출 금지
- 현재 버전: V8 (`EvaluationConst.CAL_VALUE_VERSION`)
- 새 버전 추가 시 `calPerValueVN()`, `getCalParamDataVN()`, `calPerValueListVN()` 전용 메서드 생성
- 상세: memory 디렉토리의 versioning.md 참조

## FMP API Rate Limit 규칙

- 분당 300건, 종목당 12회 호출 (V8 11회 + AnalystEstimates 1회)
- `FmpRateLimiter` 중앙 rate limiter로 관리 (TRSC_DELAY sleep은 주석 처리됨)
- 프론트 대량조회: 종목 분석 > 일괄 평가(`InvestmentEvaluation.jsx`)만 사용 — `/main/evaluate/stocks` 배치 50건, 15초 간격, **순차 호출 필수** (기존 BulkQueryModal·`/cal/per_value/abroad/arr`는 2026-10-13 삭제)
- 상세: memory 디렉토리의 fmp-rate-limit.md 참조

## API 응답 표준

새 API 작성 시 반드시 아래 포맷을 따를 것:

```json
{ "success": true, "code": "20000", "message": "정상 처리되었습니다.", "response": <data|null> }
```

## 코드 스타일

- Java: 표준 Spring Boot, Lombok (@Slf4j, @Data)
- **동작은 동일, 코드는 짧고 읽기 쉽게, 주석은 동일**
