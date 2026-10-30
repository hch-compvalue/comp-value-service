package com.finance.dart.api.domestic.valuation;

import com.finance.dart.api.common.constants.RequestContextConst;
import com.finance.dart.api.common.context.RequestContext;
import com.finance.dart.api.common.dto.CompanySharePriceResult;
import com.finance.dart.api.common.dto.CompanySharePriceResultDetail;
import com.finance.dart.api.common.service.PerShareValueCalcHelper;
import com.finance.dart.api.common.service.SectorParameterFactory;
import com.finance.dart.common.component.RedisComponent;
import com.finance.dart.common.component.RedisKeyGenerator;
import com.finance.dart.common.util.DateUtil;
import com.finance.dart.common.util.StringUtil;
import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 국내 주당가치 계산 (ROADMAP_domestic 3단계)
 * 입력 조립(우리 DB) → 계산 모델(현재 V9 + 국내 상수) → 그레이엄 → 동적 안전마진 → 매매가 — 미국 calPerValueV9 와 같은 후처리
 * - 결과는 Redis 6시간 캐시 (키에 모델 버전 포함 — 모델이 바뀌면 자동으로 새로 계산)
 * - 극단값(적정가 ≤ 0, 현재가의 10배 초과)은 결과 메시지에 표시 (6단계 검증 전 '검증 중' 단계)
 */
@Slf4j
@Service
public class KrValuationService {

    public static final String UNIT = "1";
    /** 활성 계산 모델 (국내 전용 식을 추가하면 여기만 바꾼다) */
    static final String ACTIVE_MODEL = KrV9Model.VERSION;
    static final long CACHE_HOURS = 6;
    /** 적정가가 현재가의 이 배수를 넘으면 극단값으로 표시 */
    static final BigDecimal EXTREME_MULTIPLE = BigDecimal.TEN;
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final String MESSAGE_OK = "정상 처리되었습니다.";

    private final KrValuationInputBuilder inputBuilder;
    private final PerShareValueCalcHelper calcHelper;
    private final RequestContext requestContext;
    private final RedisComponent redisComponent;
    private final Map<String, KrValuationModel> models;

    public KrValuationService(KrValuationInputBuilder inputBuilder, PerShareValueCalcHelper calcHelper,
                              RequestContext requestContext, RedisComponent redisComponent, List<KrValuationModel> modelList) {
        this.inputBuilder = inputBuilder;
        this.calcHelper = calcHelper;
        this.requestContext = requestContext;
        this.redisComponent = redisComponent;
        this.models = modelList.stream().collect(Collectors.toMap(KrValuationModel::version, Function.identity()));
    }

    /**
     * @param refresh true 면 캐시를 무시하고 다시 계산
     */
    public CompanySharePriceResult calculate(String stockCode, boolean refresh) {
        KrValuationModel model = models.get(ACTIVE_MODEL);
        String cacheKey = RedisKeyGenerator.genDomesticCompValueRstData(stockCode, model.version());
        if (!refresh) {
            String cached = redisComponent.getValue(cacheKey);
            if (!StringUtil.isStringEmpty(cached)) return new Gson().fromJson(cached, CompanySharePriceResult.class);
        }

        KrValuationInputBuilder.Input input = inputBuilder.build(stockCode, LocalDate.now(ZONE));
        CompanySharePriceResult result = new CompanySharePriceResult();
        result.set기업심볼(stockCode);
        result.set주식코드(stockCode);
        result.set버전(model.version());
        if (input.stock() != null) result.set기업명(input.stock().getStockName());
        if (input.excluded() != null) {
            result.set결과메시지("계산 제외: " + input.excluded());
            result.set상세정보(input.detail());
            return result;     // 데이터가 갖춰지면 바로 다시 계산되도록 캐시하지 않는다
        }

        String sector = input.sector().sector();
        CompanySharePriceResultDetail detail = input.detail();
        String fairValue = model.calculate(input.calParam(), detail, sector);

        calcHelper.applyGrahamScreening(stockCode, SectorParameterFactory.getParameters(sector), input.calParam(), detail, input.pbr());
        double margin = calcHelper.calculateDynamicSafetyMargin(input.beta(), detail.get그레이엄_통과수());
        String purchase = calcHelper.calculatePurchasePrice(fairValue, margin);
        String marginText = String.format("%.0f%%", margin * 100);
        detail.set안전마진율(marginText);
        setContextData(detail);

        List<String> notes = new ArrayList<>(input.warnings());
        String extreme = extremeNote(fairValue, input.price());
        if (extreme != null) notes.add(extreme);

        result.set섹터(sector != null ? sector : "미분류");
        result.set결과메시지(notes.isEmpty() ? MESSAGE_OK : MESSAGE_OK + " (참고: " + String.join(", ", notes) + ")");
        result.set주당가치(fairValue);
        result.set계산된주당가치(fairValue);
        result.set매수적정가(purchase);
        result.set목표매도가(calcHelper.calculateSellTarget(fairValue));
        result.set손절매가(calcHelper.calculateStopLoss(purchase));
        result.set안전마진율(marginText);
        result.set현재가격(BigDecimal.valueOf(input.price()).stripTrailingZeros().toPlainString());
        result.set확인시간(DateUtil.getToday("yyyy-MM-dd HH:mm:ss"));
        result.set상세정보(detail);

        redisComponent.saveValueWithTtl(cacheKey, new Gson().toJson(result), CACHE_HOURS, TimeUnit.HOURS);
        log.info("[국내 계산] {} {} 적정가 {} (현재가 {}, 섹터 {}, KSIC {})", stockCode, result.get기업명(), fairValue,
                result.get현재가격(), sector, input.sector().ksicClass());
        return result;
    }

    /** 미국 US_StockCalHelper.setRstDetailContextData 와 같은 항목 (계산 중 RequestContext 에 남긴 중간값) */
    private void setContextData(CompanySharePriceResultDetail detail) {
        detail.set영업이익_합계(requestContext.getAttributeAsString(RequestContextConst.영업이익_합계));
        detail.set영업이익_평균(requestContext.getAttributeAsString(RequestContextConst.영업이익_평균));
        detail.set계산_사업가치(StringUtil.defaultString(requestContext.getAttributeAsString(RequestContextConst.계산_사업가치), "N/A"));
        detail.set계산_재산가치(StringUtil.defaultString(requestContext.getAttributeAsString(RequestContextConst.계산_재산가치), "N/A"));
        detail.set계산_부채(StringUtil.defaultString(requestContext.getAttributeAsString(RequestContextConst.계산_부채), "N/A"));
        detail.set계산_기업가치(StringUtil.defaultString(requestContext.getAttributeAsString(RequestContextConst.계산_기업가치), "N/A"));
    }

    static String extremeNote(String fairValue, double price) {
        BigDecimal value = new BigDecimal(fairValue);
        if (value.signum() <= 0) return "극단값: 적정가 0 이하";
        if (price > 0 && value.compareTo(BigDecimal.valueOf(price).multiply(EXTREME_MULTIPLE)) > 0) return "극단값: 적정가가 현재가의 10배 초과";
        return null;
    }
}
