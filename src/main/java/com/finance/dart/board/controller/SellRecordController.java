package com.finance.dart.board.controller;

import com.finance.dart.board.dto.SellRecordDto;
import com.finance.dart.board.service.SellRecordService;
import com.finance.dart.common.dto.CommonResponse;
import com.finance.dart.common.logging.TransactionLogging;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@AllArgsConstructor
@RequestMapping("sellrecord")
@RestController
public class SellRecordController {

    private final SellRecordService sellRecordService;

    /**
     * 매도기록 등록
     * @param request
     * @param reqBody
     * @return
     */
    @TransactionLogging
    @PostMapping("/regi")
    public ResponseEntity<CommonResponse<SellRecordDto>> regiSellRecord(HttpServletRequest request, @RequestBody SellRecordDto reqBody) {

        SellRecordDto result = sellRecordService.regiSellRecord(request, reqBody);

        return new ResponseEntity<>(new CommonResponse<>(result), HttpStatus.OK);
    }

    /**
     * 매도기록 수정
     * @param request
     * @param reqBody
     * @return
     */
    @TransactionLogging
    @PostMapping("/modi")
    public ResponseEntity<CommonResponse<SellRecordDto>> modiSellRecord(HttpServletRequest request, @RequestBody SellRecordDto reqBody) {

        SellRecordDto result = sellRecordService.modiSellRecord(request, reqBody);

        return new ResponseEntity<>(new CommonResponse<>(result), HttpStatus.OK);
    }

    /**
     * 매도기록 삭제
     * @param reqBody
     * @return
     */
    @TransactionLogging
    @PostMapping("/del")
    public ResponseEntity<CommonResponse<Void>> delSellRecord(@RequestBody SellRecordDto reqBody) {

        sellRecordService.delSellRecord(reqBody);

        return new ResponseEntity<>(new CommonResponse<>(), HttpStatus.OK);
    }

    /**
     * 매도기록 목록 조회
     * @param request
     * @return
     */
    @TransactionLogging
    @GetMapping("")
    public ResponseEntity<CommonResponse<List<SellRecordDto>>> getSellRecordList(HttpServletRequest request) {

        List<SellRecordDto> response = sellRecordService.getSellRecordList(request);

        return new ResponseEntity<>(new CommonResponse<>(response), HttpStatus.OK);
    }

}