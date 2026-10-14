package com.finance.dart.stockpredictor.controller;

import com.finance.dart.stockpredictor.dto.LogContentDto;
import com.finance.dart.stockpredictor.dto.LogFileInfoDto;
import com.finance.dart.stockpredictor.dto.LogFileListDto;
import com.finance.dart.stockpredictor.dto.PredictionResponseDto;
import com.finance.dart.stockpredictor.service.MlService;
import com.finance.dart.common.config.EndPointConfig;
import com.finance.dart.common.dto.CommonResponse;
import com.finance.dart.common.logging.TransactionLogging;
import com.finance.dart.member.enums.RoleConstants;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


/**
 * 머신러닝 거래 컨트롤러
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("ml")
public class StockPredictorController {

    private final MlService mlService;

    /**
     * <pre>
     * AI 예측 - 1주일 내 최고가
     * 화면 출력용
     * </pre>
     * @param symbol
     * @return
     */
    @TransactionLogging
    @GetMapping("/predict/w")
    public ResponseEntity<CommonResponse<PredictionResponseDto>> predictForWeb(@RequestParam(name = "symbol") String symbol) {

        if(log.isDebugEnabled()) log.debug("/ml/predict 거래 요청 symbol = {}", symbol);

        try {
            PredictionResponseDto prediction = mlService.getPredictionForWeb(symbol, true);

            CommonResponse<PredictionResponseDto> response = new CommonResponse<>(prediction);
            response.setMessage("AI 예측 조회 성공");

            return new ResponseEntity<>(response, HttpStatus.OK);

        } catch (Exception e) {
            log.error("AI 예측 조회 실패 - symbol: {}, error: {}", symbol, e.getMessage(), e);

            CommonResponse<PredictionResponseDto> errorResponse = new CommonResponse<>();
            errorResponse.setMessage("AI 예측 조회 실패: " + e.getMessage());

            return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 수동 재학습 시작
     * @return 재학습 시작 결과
     */
    @EndPointConfig.RequireRole({RoleConstants.ROLE_SUPER_ADMIN})
    @TransactionLogging
    @PostMapping("/retrain")
    public ResponseEntity<CommonResponse<String>> startRetrain() {

        if(log.isDebugEnabled()) log.debug("/ml/retrain 재학습 시작 요청");

        try {
            String result = mlService.startRetrain();

            CommonResponse<String> response = new CommonResponse<>(result);
            response.setMessage("재학습 시작 요청 성공");

            return new ResponseEntity<>(response, HttpStatus.OK);

        } catch (Exception e) {
            log.error("재학습 시작 요청 실패 - error: {}", e.getMessage(), e);

            CommonResponse<String> errorResponse = new CommonResponse<>();
            errorResponse.setMessage("재학습 시작 요청 실패: " + e.getMessage());

            return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 재학습 상태 조회
     * @return 재학습 진행 상황
     */
    @EndPointConfig.RequireRole({RoleConstants.ROLE_SUPER_ADMIN})
    @TransactionLogging
    @GetMapping("/retrain/status")
    public ResponseEntity<CommonResponse<String>> getRetrainStatus() {

        if(log.isDebugEnabled()) log.debug("/ml/retrain/status 재학습 상태 조회 요청");

        try {
            String result = mlService.getRetrainStatus();

            CommonResponse<String> response = new CommonResponse<>(result);
            response.setMessage("재학습 상태 조회 성공");

            return new ResponseEntity<>(response, HttpStatus.OK);

        } catch (Exception e) {
            log.error("재학습 상태 조회 실패 - error: {}", e.getMessage(), e);

            CommonResponse<String> errorResponse = new CommonResponse<>();
            errorResponse.setMessage("재학습 상태 조회 실패: " + e.getMessage());

            return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 재학습 대상 종목 조회 (섹터별 시총 상위)
     * @return 섹터별 종목 목록
     */
    @EndPointConfig.RequireRole({RoleConstants.ROLE_SUPER_ADMIN})
    @TransactionLogging
    @GetMapping("/retrain/targets")
    public ResponseEntity<CommonResponse<String>> getRetrainTargets() {

        if(log.isDebugEnabled()) log.debug("/ml/retrain/targets 재학습 대상 종목 조회 요청");

        try {
            String result = mlService.getRetrainTargets();

            CommonResponse<String> response = new CommonResponse<>(result);
            response.setMessage("재학습 대상 종목 조회 성공");

            return new ResponseEntity<>(response, HttpStatus.OK);

        } catch (Exception e) {
            log.error("재학습 대상 종목 조회 실패 - error: {}", e.getMessage(), e);

            CommonResponse<String> errorResponse = new CommonResponse<>();
            errorResponse.setMessage("재학습 대상 종목 조회 실패: " + e.getMessage());

            return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 로그 파일 목록 조회
     * @return 로그 파일 목록
     */
    @EndPointConfig.RequireRole({RoleConstants.ROLE_ADMIN, RoleConstants.ROLE_SUPER_ADMIN})
    @TransactionLogging
    @GetMapping("/logs")
    public ResponseEntity<CommonResponse<LogFileListDto>> getLogFileList() {

        if(log.isDebugEnabled()) log.debug("/ml/logs 거래 요청");

        try {
            LogFileListDto logFileList = mlService.getLogFileList();

            CommonResponse<LogFileListDto> response = new CommonResponse<>(logFileList);
            response.setMessage("로그 파일 목록 조회 성공");

            return new ResponseEntity<>(response, HttpStatus.OK);

        } catch (Exception e) {
            log.error("로그 파일 목록 조회 실패 - error: {}", e.getMessage(), e);

            CommonResponse<LogFileListDto> errorResponse = new CommonResponse<>();
            errorResponse.setMessage("로그 파일 목록 조회 실패: " + e.getMessage());

            return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 최신 로그 파일 정보 조회
     * @return 최신 로그 파일 정보
     */
    @EndPointConfig.RequireRole({RoleConstants.ROLE_ADMIN, RoleConstants.ROLE_SUPER_ADMIN})
    @TransactionLogging
    @GetMapping("/logs/latest")
    public ResponseEntity<CommonResponse<LogFileInfoDto>> getLatestLogFileInfo() {

        if(log.isDebugEnabled()) log.debug("/ml/logs/latest 거래 요청");

        try {
            LogFileInfoDto logFileInfo = mlService.getLatestLogFileInfo();

            CommonResponse<LogFileInfoDto> response = new CommonResponse<>(logFileInfo);
            response.setMessage("최신 로그 파일 정보 조회 성공");

            return new ResponseEntity<>(response, HttpStatus.OK);

        } catch (Exception e) {
            log.error("최신 로그 파일 정보 조회 실패 - error: {}", e.getMessage(), e);

            CommonResponse<LogFileInfoDto> errorResponse = new CommonResponse<>();
            errorResponse.setMessage("최신 로그 파일 정보 조회 실패: " + e.getMessage());

            return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 로그 파일 내용 조회
     * @param filename 로그 파일명
     * @return 로그 내용
     */
    @EndPointConfig.RequireRole({RoleConstants.ROLE_ADMIN, RoleConstants.ROLE_SUPER_ADMIN})
    @TransactionLogging
    @GetMapping("/logs/{filename}")
    public ResponseEntity<CommonResponse<LogContentDto>> getLogContent(@PathVariable(name = "filename") String filename) {

        if(log.isDebugEnabled()) log.debug("/ml/logs/{} 거래 요청 - filename: {}", filename, filename);

        try {
            LogContentDto logContent = mlService.getLogContent(filename);

            CommonResponse<LogContentDto> response = new CommonResponse<>(logContent);
            response.setMessage("로그 내용 조회 성공");

            return new ResponseEntity<>(response, HttpStatus.OK);

        } catch (Exception e) {
            log.error("로그 내용 조회 실패 - filename: {}, error: {}", filename, e.getMessage(), e);

            CommonResponse<LogContentDto> errorResponse = new CommonResponse<>();
            errorResponse.setMessage("로그 내용 조회 실패: " + e.getMessage());

            return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 최신 로그 증분 조회 (폴링용)
     * @param lastLine 마지막으로 읽은 줄 번호 (0이면 초기 로드)
     * @return 최신 로그 내용
     */
    @EndPointConfig.RequireRole({RoleConstants.ROLE_ADMIN, RoleConstants.ROLE_SUPER_ADMIN})
    @TransactionLogging
    @GetMapping("/logs/stream/latest")
    public ResponseEntity<CommonResponse<LogContentDto>> getLatestLogStream(
            @RequestParam(name = "lastLine", required = false, defaultValue = "0") Integer lastLine) {

        if(log.isDebugEnabled()) log.debug("/ml/logs/stream/latest 거래 요청 - lastLine: {}", lastLine);

        try {
            LogContentDto logContent = mlService.getLatestLogStream(lastLine);

            CommonResponse<LogContentDto> response = new CommonResponse<>(logContent);
            response.setMessage("최신 로그 조회 성공");

            return new ResponseEntity<>(response, HttpStatus.OK);

        } catch (Exception e) {
            log.error("최신 로그 조회 실패 - lastLine: {}, error: {}", lastLine, e.getMessage(), e);

            CommonResponse<LogContentDto> errorResponse = new CommonResponse<>();
            errorResponse.setMessage("최신 로그 조회 실패: " + e.getMessage());

            return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

}