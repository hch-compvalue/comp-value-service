package com.finance.dart.common.component;

import lombok.AllArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 환경변수
 */
@Component
@AllArgsConstructor
public class ConfigComponent {

    private final Environment env;

    /**
     * OPEN DART API KEY
     * @return
     */
    public String getDartApiKey() {
        return env.getProperty("OPEN_DART_API_KEY");
    }

    /**
     * FMP API KEY
     * @return
     */
    public String getFmpApiKey() {
        return env.getProperty("FMP_API_KEY");
    }

    /**
     * 오픈 API API KEY
     * @return
     */
    public String getOpenAiApiKey() {
        return env.getProperty("OPEN_AI_API_KEY");
    }

    /**
     * 업비트 ACCECC KEY
     * @return
     */
    public String getUpbitAccessKey() { return env.getProperty("UPBIT_OPEN_API_ACCESS_KEY"); }

    /**
     * 업비트 SECRET KEY
     * @return
     */
    public String getUpbitSecretKey() { return env.getProperty("UPBIT_OPEN_API_SECRET_KEY"); }

    /**
     * 한국투자증권(KIS) APP KEY (trader와 공유, 토큰 발급은 trader만)
     * @return
     */
    public String getKisAppKey() { return env.getProperty("KIS_APP_KEY"); }

    /**
     * 한국투자증권(KIS) APP SECRET
     * @return
     */
    public String getKisAppSecret() { return env.getProperty("KIS_APP_SECRET"); }

    /**
     * trader 내부 API 공유 비밀값 (KIS 토큰 조회용)
     * @return
     */
    public String getTraderInternalToken() { return env.getProperty("TRADER_INTERNAL_TOKEN"); }
}
