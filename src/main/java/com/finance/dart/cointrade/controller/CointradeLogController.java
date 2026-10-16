package com.finance.dart.cointrade.controller;


import com.finance.dart.cointrade.dto.CointradeLogContentDto;
import com.finance.dart.cointrade.dto.CointradeLogFileInfoDto;
import com.finance.dart.cointrade.dto.CointradeLogFileListDto;
import com.finance.dart.cointrade.exception.TraderLogException;
import com.finance.dart.cointrade.service.CointradeLogService;
import com.finance.dart.common.dto.CommonResponse;
import com.finance.dart.common.logging.TransactionLogging;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


/**
 * 코인 자동매매 프로그램 로그 컨트롤러
 */
@Slf4j
@RestController
@RequestMapping("/api/cointrade/log")
@RequiredArgsConstructor
public class CointradeLogController {

    private final CointradeLogService logService;

    /**
     * 로그 파일 목록 조회
     */
    @TransactionLogging
    @GetMapping("/logs")
    public ResponseEntity<CommonResponse<CointradeLogFileListDto>> getLogFileList() {
        if(log.isDebugEnabled()) log.debug("/api/cointrade/logs 거래 요청");

        try {
            CointradeLogFileListDto logFileList = logService.getLogFileList();
            CommonResponse<CointradeLogFileListDto> response = new CommonResponse<>(logFileList);
            response.setMessage("로그 파일 목록 조회 성공");
            return new ResponseEntity<>(response, HttpStatus.OK);
        } catch (TraderLogException e) {
            log.error("로그 파일 목록 조회 실패 - error: {}", e.getMessage(), e);
            CommonResponse<CointradeLogFileListDto> errorResponse = new CommonResponse<>();
            errorResponse.setMessage("로그 파일 목록 조회 실패: " + e.getMessage());
            return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 최신 로그 파일 정보 조회
     */
    @TransactionLogging
    @GetMapping("/logs/latest")
    public ResponseEntity<CommonResponse<CointradeLogFileInfoDto>> getLatestLogFileInfo() {
        if(log.isDebugEnabled()) log.debug("/api/cointrade/logs/latest 거래 요청");

        try {
            CointradeLogFileInfoDto logFileInfo = logService.getLatestLogFileInfo();
            CommonResponse<CointradeLogFileInfoDto> response = new CommonResponse<>(logFileInfo);
            response.setMessage("최신 로그 파일 정보 조회 성공");
            return new ResponseEntity<>(response, HttpStatus.OK);
        } catch (TraderLogException e) {
            log.error("최신 로그 파일 정보 조회 실패 - error: {}", e.getMessage(), e);
            CommonResponse<CointradeLogFileInfoDto> errorResponse = new CommonResponse<>();
            errorResponse.setMessage("최신 로그 파일 정보 조회 실패: " + e.getMessage());
            return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 로그 파일 내용 조회
     */
    @TransactionLogging
    @GetMapping("/logs/{filename}")
    public ResponseEntity<CommonResponse<CointradeLogContentDto>> getLogContent(@PathVariable(name = "filename") String filename) {
        if(log.isDebugEnabled()) log.debug("/api/cointrade/logs/{} 거래 요청", filename);

        try {
            CointradeLogContentDto logContent = logService.getLogContent(filename);
            CommonResponse<CointradeLogContentDto> response = new CommonResponse<>(logContent);
            response.setMessage("로그 내용 조회 성공");
            return new ResponseEntity<>(response, HttpStatus.OK);
        } catch (TraderLogException e) {
            log.error("로그 내용 조회 실패 - filename: {}, error: {}", filename, e.getMessage(), e);
            CommonResponse<CointradeLogContentDto> errorResponse = new CommonResponse<>();
            errorResponse.setMessage("로그 내용 조회 실패: " + e.getMessage());
            return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** initialLines
     * 최신 로그 증분 조회 (폴링용)
     */
    @TransactionLogging
    @GetMapping("/logs/stream/latest")
    public ResponseEntity<CommonResponse<CointradeLogContentDto>> getLatestLogStream(
            @RequestParam(name = "lastLine", required = false, defaultValue = "0") Integer lastLine,
            @RequestParam(name = "initialLines", required = false, defaultValue = "2000") Integer initialLines
    ) {
        if(log.isDebugEnabled()) log.debug("/api/cointrade/logs/stream/latest 거래 요청 - lastLine: {}", lastLine);

        try {
            CointradeLogContentDto logContent = logService.getLatestLogStream(lastLine, initialLines);
            CommonResponse<CointradeLogContentDto> response = new CommonResponse<>(logContent);
            response.setMessage("최신 로그 조회 성공");
            return new ResponseEntity<>(response, HttpStatus.OK);
        } catch (TraderLogException e) {
            log.error("최신 로그 조회 실패 - lastLine: {}, initialLines: {}, error: {}", lastLine, initialLines, e.getMessage(), e);
            CommonResponse<CointradeLogContentDto> errorResponse = new CommonResponse<>();
            errorResponse.setMessage("최신 로그 조회 실패: " + e.getMessage());
            return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
