package com.finance.dart.api.abroad.controller;

import com.finance.dart.api.abroad.dto.fmp.stocknews.StockNewsReqDto;
import com.finance.dart.api.abroad.dto.fmp.stocknews.StockNewsResDto;
import com.finance.dart.api.abroad.service.fmp.StockNewsService;
import com.finance.dart.common.dto.CommonResponse;
import com.finance.dart.common.logging.TransactionLogging;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * FMP 조회 API (화면에서 사용하는 엔드포인트만 노출)
 * - 재무제표/시세/비율 등 FMP 원시 조회는 서비스 계층에서 내부 호출로만 사용한다.
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("abroad/company/financial/fmp")
public class FinStatFmpController {

    private final StockNewsService stockNewsService;                            // 종목별 뉴스 조회 서비스


    /**
     * 종목별 최신 뉴스 조회
     * @param requestDto tickers(심볼), limit(건수)
     * @return 뉴스 목록
     */
    @TransactionLogging
    @PostMapping("/stockNews")
    public ResponseEntity<CommonResponse<List<StockNewsResDto>>> stockNews(@RequestBody StockNewsReqDto requestDto) {

        List<StockNewsResDto> response = stockNewsService.findStockNews(requestDto);

        return new ResponseEntity<>(new CommonResponse<>(response), HttpStatus.OK);
    }

}
