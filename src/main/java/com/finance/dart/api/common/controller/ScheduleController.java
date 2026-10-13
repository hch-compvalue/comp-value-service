package com.finance.dart.api.common.controller;

import com.finance.dart.api.common.service.schedule.RecommendedStocks;
import com.finance.dart.common.config.EndPointConfig;
import com.finance.dart.common.logging.TransactionLogging;
import com.finance.dart.common.util.StringUtil;
import com.finance.dart.member.enums.RoleConstants;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@AllArgsConstructor
@RestController
@RequestMapping("schd")
public class ScheduleController {

    private final RecommendedStocks recommendedStocks;


    /**
     * 추천 종목 스케줄러 수동 실행(미국)
     * @param body maxCount: 최대 처리 건수 (기본값 5, 0이면 전체)
     * @return 스케줄 실행 메시지
     */
    @EndPointConfig.RequireRole({RoleConstants.ROLE_SUPER_ADMIN})
    @TransactionLogging
    @PostMapping("/recommended-stocks")
    public ResponseEntity<Object> recommendedStocksTest(@RequestBody(required = false) Map<String, Object> body) {

        int maxCount = 5; // 테스트 기본값
        if (body != null && body.get("maxCount") != null) {
            maxCount = Integer.parseInt(StringUtil.defaultString(body.get("maxCount")));
        }

        recommendedStocks.startScheduledTask(maxCount);

        return new ResponseEntity<>("추천종목 스케줄 실행됨!! (maxCount=" + maxCount + ", 로그 확인)", HttpStatus.OK);
    }

}