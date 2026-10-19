package com.finance.dart.board.service;

import com.finance.dart.api.abroad.dto.fmp.forexquote.ForexQuoteReqDto;
import com.finance.dart.api.abroad.dto.fmp.forexquote.ForexQuoteResDto;
import com.finance.dart.api.abroad.dto.fmp.quote.AfterTradeReqDto;
import com.finance.dart.api.abroad.dto.fmp.quote.AfterTradeResDto;
import com.finance.dart.api.abroad.dto.fmp.quote.StockQuoteReqDto;
import com.finance.dart.api.abroad.dto.fmp.quote.StockQuoteResDto;
import com.finance.dart.api.abroad.service.fmp.AfterTradeService;
import com.finance.dart.api.abroad.service.fmp.CompanyProfileSearchService;
import com.finance.dart.api.abroad.service.fmp.ForexQuoteService;
import com.finance.dart.api.abroad.service.fmp.StockQuoteService;
import com.finance.dart.api.abroad.service.US_StockCalFromFpmService;
import com.finance.dart.board.dto.TranRecordCurValueResDto;
import com.finance.dart.board.dto.TranRecordDto;
import com.finance.dart.board.dto.TranRecordFxRateResDto;
import com.finance.dart.board.entity.TranRecordEntity;
import com.finance.dart.board.repository.TranRecordRepository;
import com.finance.dart.api.common.constants.EvaluationConst;
import com.finance.dart.api.common.dto.CompanySharePriceResult;
import com.finance.dart.common.component.RedisComponent;
import com.finance.dart.common.component.RedisKeyGenerator;
import com.finance.dart.common.util.ConvertUtil;
import com.finance.dart.common.util.DateUtil;
import com.finance.dart.common.exception.BizException;
import com.finance.dart.common.util.StringUtil;
import com.google.gson.Gson;
import com.finance.dart.member.dto.Member;
import com.finance.dart.member.entity.MemberEntity;
import com.finance.dart.member.service.MemberService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Slf4j
@AllArgsConstructor
@Service
public class TranRecordService {

    private final TranRecordRepository tranRecordRepository;

    private final MemberService memberService;
    private final RedisComponent redisComponent;                                // Redis 컴포넌트
    private final CompanyProfileSearchService companyProfileSearchService;      // 기업 프로파일 검색 서비스
    private final ForexQuoteService forexQuoteService;                          // 외환시세 조회 서비스
    private final StockQuoteService stockQuoteService;                          // 주식시세조회(간소화)
    private final AfterTradeService afterTradeService;                          // 애프터마켓 시세 조회 서비스
    private final US_StockCalFromFpmService usStockCalFromFpmService;           // 해외기업 주당가치 계산 서비스
    private final SymbolMemoService symbolMemoService;                          // 종목 메모 서비스

    private final Gson gson = new Gson();

    // 병렬 처리용 스레드 풀 (최대 10개 동시 실행)
    private final ExecutorService executorService = Executors.newFixedThreadPool(10);


    /**
     * 거래기록 등록
     * @param tranRecordDto
     * @return
     */
    public TranRecordDto regiTranRecord(HttpServletRequest request, TranRecordDto tranRecordDto) {

        // 로그인 회원 정보
        MemberEntity memberEntity = getMemberEntity(request);

        // DTO -> Entity 변환
        TranRecordEntity tranRecordEntity = ConvertUtil.parseObject(tranRecordDto, TranRecordEntity.class);
        tranRecordEntity.setMember(memberEntity);

        // 데이터 저장
        TranRecordEntity savedEntity = tranRecordRepository.save(tranRecordEntity);

        // Entity -> DTO 변환하여 반환
        return ConvertUtil.parseObject(savedEntity, TranRecordDto.class);
    }

    /**
     * 거래기록 수정
     * @param request
     * @param updateDto
     * @return
     */
    public TranRecordDto modiTranRecord(HttpServletRequest request, TranRecordDto updateDto) {

        // 로그인 회원 정보
        MemberEntity memberEntity = getMemberEntity(request);

        // 데이터 조회 (본인 기록만)
        Optional<TranRecordEntity> dataOpt = tranRecordRepository.findById(updateDto.getId());
        if(dataOpt.isEmpty()) return null;

        // 데이터 수정
        TranRecordEntity data = dataOpt.get();
        checkOwner(data, memberEntity.getId());
        String oldSymbol = data.getSymbol();
        data.setMember(memberEntity);

        if(updateDto.getSymbol() != null) data.setSymbol(updateDto.getSymbol());
        if(updateDto.getCompanyName() != null) data.setCompanyName(updateDto.getCompanyName());
        if(updateDto.getBuyDate() != null) data.setBuyDate(updateDto.getBuyDate());
        if(updateDto.getBuyPrice() != null) data.setBuyPrice(updateDto.getBuyPrice());
        if(updateDto.getTotalBuyAmount() != null) data.setTotalBuyAmount(updateDto.getTotalBuyAmount());
        if(updateDto.getTargetPrice() != null) data.setTargetPrice(updateDto.getTargetPrice());
        if(updateDto.getTargetPriceSync() != null) data.setTargetPriceSync(updateDto.getTargetPriceSync());
        if(updateDto.getBuyExchangeRateAtTrade() != null) data.setBuyExchangeRateAtTrade(updateDto.getBuyExchangeRateAtTrade());
        if(updateDto.getRmk() != null) data.setRmk(updateDto.getRmk());
        data.setUpdatedAt(LocalDateTime.now());

        // 동기화 ON이면 Redis 목표매도가를 DB에도 저장 (캐시 만료 시 마지막 동기화 값 표시용)
        boolean sync = Boolean.TRUE.equals(data.getTargetPriceSync());
        Double sellTarget = sync ? getSellTargetPrices(Set.of(data.getSymbol())).get(data.getSymbol()) : null;
        if(sellTarget != null) data.setTargetPrice(sellTarget);

        TranRecordEntity savedEntity = tranRecordRepository.save(data);

        // 티커 변경 시 종목 메모 이동/정리
        symbolMemoService.handleSymbolChange(memberEntity.getId(), oldSymbol, savedEntity.getSymbol());

        // Entity -> DTO 변환하여 반환
        TranRecordDto resultDto = ConvertUtil.parseObject(savedEntity, TranRecordDto.class);
        if(sync) resultDto.setTargetPriceCached(sellTarget != null);

        return resultDto;
    }

    /**
     * 거래기록 삭제 (본인 기록만, 해당 심볼 보유분이 모두 없어지면 종목 메모도 삭제)
     * @param request
     * @param deleteDto
     */
    public void delTranRecord(HttpServletRequest request, TranRecordDto deleteDto) {

        if (deleteDto.getId() == null) return;

        Long memberId = getMemberEntity(request).getId();
        Optional<TranRecordEntity> dataOpt = tranRecordRepository.findById(deleteDto.getId());
        if (dataOpt.isEmpty()) return;

        TranRecordEntity data = dataOpt.get();
        checkOwner(data, memberId);
        tranRecordRepository.delete(data);

        symbolMemoService.deleteIfNotHeld(memberId, data.getSymbol());
    }

    /**
     * 본인 거래기록인지 확인
     */
    private void checkOwner(TranRecordEntity entity, Long memberId) {
        if (entity.getMember() == null || !entity.getMember().getId().equals(memberId)) {
            throw new BizException("본인 거래기록만 수정/삭제할 수 있습니다.");
        }
    }


    /**
     * 거래기록 목록 조회
     * @param request
     * @return
     */
    public List<TranRecordDto> getTranRecordList(HttpServletRequest request) {

        List<TranRecordDto> tranRecordDtoList = new LinkedList<>();

        // 로그인 회원정보
        Member member = memberService.getLoginMember(request);
        Long memberId = member.getId();

        // 거래기록 조회
        List<TranRecordEntity> tranRecordEntityList = tranRecordRepository.findByMember_Id(memberId);

        // 티커 분류 (중복 제거)
        Set<String> uniqueTickers = new LinkedHashSet<>();
        for(TranRecordEntity tranRecordEntity : tranRecordEntityList) {
            uniqueTickers.add(tranRecordEntity.getSymbol());
        }

        // 현재가 조회 (병렬 처리)
        List<TranRecordCurValueResDto> curValues = getCurValues(uniqueTickers);

        // 티커별 현재가 맵 구성
        Map<String, Double> tickerValueMap = new HashMap<>();
        if(curValues != null) {
            for(TranRecordCurValueResDto curValue : curValues) {
                tickerValueMap.put(curValue.getSymbol(),
                    (curValue.getCurrentPrice() == null) ? 0.0 : curValue.getCurrentPrice());
            }
        }

        // 목표매도가 동기화 대상 심볼 수집
        Set<String> syncSymbols = new LinkedHashSet<>();
        for(TranRecordEntity entity : tranRecordEntityList) {
            if(Boolean.TRUE.equals(entity.getTargetPriceSync())) {
                syncSymbols.add(entity.getSymbol());
            }
        }

        // Redis에서 목표매도가 조회 (심볼별 캐시)
        Map<String, Double> sellTargetMap = getSellTargetPrices(syncSymbols);

        // 현재가 + 목표매도가 동기화 세팅
        List<TranRecordEntity> syncedEntityList = new ArrayList<>();
        for(TranRecordEntity tranRecordEntity : tranRecordEntityList) {
            TranRecordDto tranRecordDto = ConvertUtil.parseObject(tranRecordEntity, TranRecordDto.class);
            tranRecordDto.setCurrentPrice(tickerValueMap.getOrDefault(tranRecordDto.getSymbol(), 0.0));

            // 동기화 ON인 종목: Redis에서 조회된 목표매도가로 덮어쓰기 (캐시 없으면 DB의 마지막 동기화 값 유지)
            if(Boolean.TRUE.equals(tranRecordDto.getTargetPriceSync())) {
                Double sellTarget = sellTargetMap.get(tranRecordDto.getSymbol());
                tranRecordDto.setTargetPriceCached(sellTarget != null);
                if(sellTarget != null) {
                    tranRecordDto.setTargetPrice(sellTarget);
                    if(!sellTarget.equals(tranRecordEntity.getTargetPrice())) {
                        tranRecordEntity.setTargetPrice(sellTarget);
                        syncedEntityList.add(tranRecordEntity);
                    }
                }
            }

            tranRecordDtoList.add(tranRecordDto);
        }

        // 바뀐 동기화 목표가만 DB 저장
        if(!syncedEntityList.isEmpty()) tranRecordRepository.saveAll(syncedEntityList);

        return tranRecordDtoList;
    }

    /**
     * 동기화 목표매도가 재계산 (캐시 만료 종목 상세 모달 진입 시)
     * - 최신 버전 주당가치 계산(Redis 캐시 재생성) 후 회원의 동기화 ON 거래기록 DB 목표가 갱신
     * @param request
     * @param symbol
     * @return symbol, targetPrice(계산 실패 시 null), targetPriceCached
     */
    public TranRecordDto syncTargetPrice(HttpServletRequest request, String symbol) throws Exception {

        if(StringUtil.isStringEmpty(symbol)) throw new BizException("티커 정보가 존재하지 않습니다.");

        Member member = memberService.getLoginMember(request);

        Double sellTarget = parseSellTarget(usStockCalFromFpmService.calPerValue(symbol));

        TranRecordDto resultDto = new TranRecordDto();
        resultDto.setSymbol(symbol);
        resultDto.setTargetPrice(sellTarget);
        resultDto.setTargetPriceCached(sellTarget != null);
        if(sellTarget == null) return resultDto;

        List<TranRecordEntity> syncedEntityList = new ArrayList<>();
        for(TranRecordEntity entity : tranRecordRepository.findByMember_Id(member.getId())) {
            if(symbol.equals(entity.getSymbol()) && Boolean.TRUE.equals(entity.getTargetPriceSync())
                    && !sellTarget.equals(entity.getTargetPrice())) {
                entity.setTargetPrice(sellTarget);
                syncedEntityList.add(entity);
            }
        }
        tranRecordRepository.saveAll(syncedEntityList);

        return resultDto;
    }

    /**
     * 현재가격 조회(단건)
     * @param symbol
     * @return
     */
    public TranRecordCurValueResDto getCurValue(String symbol) {

        if(!StringUtil.isStringEmpty(symbol)) {
            Set<String> symbolSet = new LinkedHashSet<>();
            symbolSet.add(symbol);

            List<TranRecordCurValueResDto> values = getCurValues(symbolSet);
            if(values == null || values.isEmpty()) return null;

            return values.get(0);
        }

        return null;
    }

    /**
     * 현재가격 조회(다건) - 병렬 처리 버전
     * @param symbolSet
     * @return
     */
    public List<TranRecordCurValueResDto> getCurValues(Set<String> symbolSet) {

        if (symbolSet == null || symbolSet.isEmpty()) {
            return null;
        }

        List<String> symbolList = symbolSet.stream().toList();

        // 각 심볼에 대해 병렬로 API 호출
        List<CompletableFuture<TranRecordCurValueResDto>> futures = symbolList.stream()
                .map(symbol -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return fetchCurrentPriceForSymbol(symbol);
                    } catch (Exception e) {
                        log.error("심볼 {} 가격 조회 중 오류 발생: {}", symbol, e.getMessage());
                        // 오류 발생 시 기본값 반환
                        TranRecordCurValueResDto errorDto = new TranRecordCurValueResDto();
                        errorDto.setSymbol(symbol);
                        errorDto.setCurrentPrice(0.0);
                        errorDto.setUpdatedAt(DateUtil.getToday("yyyy-MM-dd HH:mm:ss"));
                        return errorDto;
                    }
                }, executorService))
                .collect(Collectors.toList());

        // 모든 비동기 작업이 완료될 때까지 대기하고 결과 수집
        return futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());
    }

    /**
     * 단일 심볼에 대한 현재가 조회 (병렬 처리용 헬퍼 메서드)
     * @param symbol
     * @return
     */
    private TranRecordCurValueResDto fetchCurrentPriceForSymbol(String symbol) {
        TranRecordCurValueResDto tranRecordCurValueResDto = new TranRecordCurValueResDto();
        tranRecordCurValueResDto.setSymbol(symbol);

        // 정규장과 애프터장 API 호출 (각 심볼에 대해 병렬로 실행됨)
        List<StockQuoteResDto> stockQuoteResDtoList = stockQuoteService.findStockQuote(new StockQuoteReqDto(symbol));
        List<AfterTradeResDto> afterTradeResDtoList = afterTradeService.findAfterTrade(new AfterTradeReqDto(symbol));

        // 정규장 데이터 처리
        if(stockQuoteResDtoList == null || stockQuoteResDtoList.isEmpty()) {
            tranRecordCurValueResDto.setCurrentPrice(0.0);
        } else {
            StockQuoteResDto stockQuoteResDto = stockQuoteResDtoList.get(0);
            tranRecordCurValueResDto.setCurrentPrice(stockQuoteResDto.getPrice());
            tranRecordCurValueResDto.setPreviousClose(stockQuoteResDto.getPreviousClose());

            // 애프터장 데이터가 있고 더 최신이면 업데이트
            if(afterTradeResDtoList != null && !afterTradeResDtoList.isEmpty()) {
                AfterTradeResDto afterTradeResDto = afterTradeResDtoList.get(0);

                long quoteTimestamp = stockQuoteResDto.getTimestamp();      // 정규장 (초 단위)
                long afterTradeTimestamp = afterTradeResDto.getTimestamp(); // 애프터마켓 (밀리초 단위)

                // 단위 통일: 밀리초 → 초로 변환하여 비교
                long afterTradeTimestampSec = afterTradeTimestamp > 9_999_999_999L
                        ? afterTradeTimestamp / 1000
                        : afterTradeTimestamp;

                if(afterTradeTimestampSec > quoteTimestamp) {  // 애프터마켓이 더 최신
                    tranRecordCurValueResDto.setCurrentPrice(afterTradeResDto.getPrice());
                }
            }
        }

        tranRecordCurValueResDto.setUpdatedAt(DateUtil.getToday("yyyy-MM-dd HH:mm:ss"));
        return tranRecordCurValueResDto;
    }

    /**
     * 환율 조회
     * @param currency
     * @return
     */
    public TranRecordFxRateResDto getFxRate(String currency) {

        TranRecordFxRateResDto result = new TranRecordFxRateResDto();

        ForexQuoteReqDto forexQuoteReqDto = new ForexQuoteReqDto(currency);
        List<ForexQuoteResDto> resList = forexQuoteService.findForexQuote(forexQuoteReqDto);

        if(resList == null || resList.size() == 0) return null;

        ForexQuoteResDto forexQuoteResDto = resList.get(0);

        result.setRate(forexQuoteResDto.getPrice());
        result.setUpdatedAt(DateUtil.getToday("yyyy-MM-dd HH:mm:ss"));

        return result;
    }

    /**
     * Redis에서 심볼별 목표매도가 조회
     * Redis 키: compvalue:abroad:calvalue:{CAL_VALUE_VERSION}:{symbol}
     * @param symbols 조회할 심볼 셋
     * @return 심볼별 목표매도가 맵
     */
    private Map<String, Double> getSellTargetPrices(Set<String> symbols) {
        Map<String, Double> result = new HashMap<>();
        if(symbols == null || symbols.isEmpty()) return result;

        for(String symbol : symbols) {
            try {
                String redisKey = RedisKeyGenerator.genAbroadCompValueRstData(symbol, EvaluationConst.CAL_VALUE_VERSION);
                String cachedData = redisComponent.getValue(redisKey);
                if(!StringUtil.isStringEmpty(cachedData)) {
                    Double sellTarget = parseSellTarget(gson.fromJson(cachedData, CompanySharePriceResult.class));
                    if(sellTarget != null) result.put(symbol, sellTarget);
                }
            } catch (Exception e) {
                log.warn("심볼 {} 목표매도가 Redis 조회 실패: {}", symbol, e.getMessage());
            }
        }

        return result;
    }

    /**
     * 주당가치 계산 결과에서 목표매도가 추출
     * @return 양수 목표매도가, 없거나 0 이하면 null
     */
    private Double parseSellTarget(CompanySharePriceResult priceResult) {
        if(priceResult == null || StringUtil.isStringEmpty(priceResult.get목표매도가())) return null;
        try {
            double sellTarget = Double.parseDouble(priceResult.get목표매도가());
            return sellTarget > 0 ? sellTarget : null;
        } catch (NumberFormatException e) {
            log.warn("심볼 {} 목표매도가 형식 오류: {}", priceResult.get기업심볼(), priceResult.get목표매도가());
            return null;
        }
    }

    /**
     * 로그인 회원정보 조회
     * @param request
     * @return
     */
    private MemberEntity getMemberEntity(HttpServletRequest request) {
        // 로그인 회원 정보
        Member member = memberService.getLoginMember(request);
        if (member == null) {
            throw new RuntimeException("로그인이 필요합니다.");
        }

        MemberEntity memberEntity = new MemberEntity();
        memberEntity.setId(member.getId());
        memberEntity.setUsername(member.getUsername());
        memberEntity.setNickname(member.getNickname());

        return memberEntity;
    }
}
