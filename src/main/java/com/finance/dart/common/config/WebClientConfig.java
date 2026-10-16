package com.finance.dart.common.config;

import com.finance.dart.monitoring.tracker.DownstreamTrafficTracker;
import com.finance.dart.monitoring.tracker.RequestTrafficTracker;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
@RequiredArgsConstructor
public class WebClientConfig {

    private final DownstreamTrafficTracker downstreamTrafficTracker;

    @Bean
    public WebClient webClient() {
        // HTTP 클라이언트 타임아웃 설정
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)  // 연결 타임아웃: 5초
                .responseTimeout(Duration.ofSeconds(5))              // 응답 타임아웃: 5초
                .doOnConnected(conn ->
                        conn.addHandlerLast(new ReadTimeoutHandler(5, TimeUnit.SECONDS))   // 읽기 타임아웃: 5초
                                .addHandlerLast(new WriteTimeoutHandler(5, TimeUnit.SECONDS)));  // 쓰기 타임아웃: 5초

        // WebClient 빈 생성 및 반환
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .filter(downstreamTrackingFilter())
                .build();
    }

    /**
     * WebClient 호출 URL로 다운스트림 서비스 트래픽 추적
     * 사용자 HTTP 요청 컨텍스트에서만 카운트 (모니터링 헬스체크 제외)
     */
    private ExchangeFilterFunction downstreamTrackingFilter() {
        return ExchangeFilterFunction.ofRequestProcessor(clientRequest -> {
            if (RequestTrafficTracker.isInUserRequest()) {
                String url = clientRequest.url().toString();
                if (url.contains("18082") || url.contains("trader-service")) {
                    downstreamTrafficTracker.trackTrader();
                } else if (url.contains("18081") || url.contains("stock-predictor") || url.contains("predictor")) {
                    downstreamTrafficTracker.trackMl();
                }
            }
            return Mono.just(clientRequest);
        });
    }
}
