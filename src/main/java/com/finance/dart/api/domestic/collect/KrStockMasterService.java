package com.finance.dart.api.domestic.collect;

import com.finance.dart.api.domestic.dto.CorpCodeDTO;
import com.finance.dart.api.domestic.entity.KrStockEntity;
import com.finance.dart.api.domestic.enums.KrExchange;
import com.finance.dart.api.domestic.repository.KrStockRepository;
import com.finance.dart.api.domestic.service.CorpCodeService;
import com.finance.dart.common.component.ConfigComponent;
import lombok.RequiredArgsConstructor;
import org.json.JSONException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 국내 종목 마스터 갱신
 * - KIS 마스터 파일(코스피·코스닥) → 보통주 목록, OpenDart 고유번호로 corp_code 매핑
 * - 마스터에서 사라진 종목은 active=N (삭제 안 함)
 * - 파일이 깨져 종목 수가 비정상으로 적으면 갱신하지 않는다 (전 종목이 비활성으로 바뀌는 사고 방지)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KrStockMasterService {

    /** 2026-10 기준 보통주 약 2,534개 — 이보다 크게 적으면 파일 이상으로 판단 */
    static final int MIN_COMMON_STOCKS = 2_000;
    private static final String CORP_CODE_URL = "https://opendart.fss.or.kr/api/corpCode.xml?crtfc_key=";
    private static final String YES = "Y";
    private static final String NO = "N";
    private static final int STOCK_CODE_LENGTH = 6;

    private final KrZipDownloader downloader;
    private final CorpCodeService corpCodeService;
    private final ConfigComponent configComponent;
    private final KrStockRepository krStockRepository;

    /** 갱신 결과 */
    public record Result(int total, int added, int deactivated, int corpMapped, boolean corpMappingFailed) {
        public String summary() {
            return String.format("종목 %d (신규 %d, 비활성 %d, 고유번호 %d%s)",
                    total, added, deactivated, corpMapped, corpMappingFailed ? " — 고유번호 갱신 실패, 기존값 유지" : "");
        }
    }

    @Transactional
    public Result refresh() {
        List<KrStockMasterParser.Row> rows = new ArrayList<>();
        for (KrExchange exchange : KrExchange.values()) {
            byte[] zip = downloader.download(exchange.getMasterUrl(), exchange.name() + " 종목 마스터");
            rows.addAll(KrStockMasterParser.parseZip(zip, exchange));
        }
        if (rows.size() < MIN_COMMON_STOCKS) {
            throw new KrCollectException("종목 마스터 보통주 " + rows.size() + "개 — 최소 " + MIN_COMMON_STOCKS + "개 미만이라 갱신 중단");
        }

        Map<String, String> corpCodes = loadCorpCodes();
        Map<String, KrStockEntity> existing = krStockRepository.findAll().stream()
                .collect(Collectors.toMap(KrStockEntity::getStockCode, Function.identity()));

        int added = 0;
        Set<String> seen = new HashSet<>();
        List<KrStockEntity> toSave = new ArrayList<>();
        for (KrStockMasterParser.Row row : rows) {
            if (!seen.add(row.stockCode())) continue;   // 코스피·코스닥 중복 방지
            KrStockEntity entity = existing.get(row.stockCode());
            if (entity == null) {
                entity = new KrStockEntity();
                entity.setStockCode(row.stockCode());
                added++;
            }
            apply(entity, row, corpCodes);
            toSave.add(entity);
        }

        int deactivated = 0;
        for (KrStockEntity entity : existing.values()) {
            if (!seen.contains(entity.getStockCode()) && YES.equals(entity.getActive())) {
                entity.setActive(NO);
                toSave.add(entity);
                deactivated++;
            }
        }
        krStockRepository.saveAll(toSave);

        int corpMapped = (int) toSave.stream().filter(e -> YES.equals(e.getActive()) && e.getCorpCode() != null).count();
        Result result = new Result(seen.size(), added, deactivated, corpMapped, corpCodes == null);
        log.info("[국내 수집] 종목 마스터 갱신 — {}", result.summary());
        return result;
    }

    private static void apply(KrStockEntity entity, KrStockMasterParser.Row row, Map<String, String> corpCodes) {
        entity.setStockName(row.stockName());
        entity.setExchange(row.exchange().name());
        entity.setIndexLarge(row.indexLarge());
        entity.setIndexMid(row.indexMid());
        entity.setIndexSmall(row.indexSmall());
        entity.setListedDate(row.listedDate());
        entity.setFiscalMonth(row.fiscalMonth());
        entity.setTradingHalt(row.tradingHalt());
        entity.setAdministrative(row.administrative());
        entity.setActive(YES);
        if (corpCodes != null) entity.setCorpCode(corpCodes.get(row.stockCode()));
    }

    /**
     * 종목코드 → OpenDart 고유번호. 실패하면 null (기존 corp_code 유지, 2-A 수집은 계속)
     */
    private Map<String, String> loadCorpCodes() {
        try {
            byte[] zip = downloader.download(CORP_CODE_URL + configComponent.getDartApiKey(), "OpenDart 고유번호");
            List<CorpCodeDTO> list = corpCodeService.parseCorpCodeZip(zip, true).getList();
            Map<String, String> result = new HashMap<>();
            for (CorpCodeDTO dto : list) {
                String code = normalizeStockCode(dto.getStockCode());
                if (code != null) result.put(code, dto.getCorpCode());
            }
            return result;
        } catch (KrCollectException | JSONException e) {   // JSONException: 응답 XML 형식 이상
            log.warn("[국내 수집] OpenDart 고유번호 갱신 실패 — 기존값 유지: {}", e.getMessage());
            return null;
        }
    }

    /** XML→JSON 변환에서 숫자 코드는 앞자리 0이 빠질 수 있어 6자리로 맞춘다 (영문 섞인 코드는 그대로) */
    static String normalizeStockCode(String raw) {
        if (raw == null) return null;
        String code = raw.trim();
        if (code.matches("\\d{1," + STOCK_CODE_LENGTH + "}")) return "0".repeat(STOCK_CODE_LENGTH - code.length()) + code;
        return code.matches(KrStockMasterParser.STOCK_CODE_PATTERN) ? code : null;
    }
}
