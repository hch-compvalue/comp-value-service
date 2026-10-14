package com.finance.dart.api.common.controller;

import com.finance.dart.api.common.dto.RecommendProfileDto;
import com.finance.dart.api.common.service.RecommendProfileService;
import com.finance.dart.common.config.EndPointConfig;
import com.finance.dart.common.dto.CommonResponse;
import com.finance.dart.common.logging.TransactionLogging;
import com.finance.dart.member.enums.RoleConstants;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 추천종목 프로파일 컨트롤러
 */
@Slf4j
@AllArgsConstructor
@RequestMapping("recommend/profile")
@RestController
public class RecommendProfileController {

    private final RecommendProfileService recommendProfileService;

    /**
     * 프로파일 등록
     */
    @EndPointConfig.RequireRole({RoleConstants.ROLE_SUPER_ADMIN})
    @TransactionLogging
    @PostMapping("/regi")
    public ResponseEntity<CommonResponse<RecommendProfileDto>> regiProfile(@RequestBody RecommendProfileDto reqBody) {

        RecommendProfileDto result = recommendProfileService.regiProfile(reqBody);

        return new ResponseEntity<>(new CommonResponse<>(result), HttpStatus.OK);
    }

    /**
     * 프로파일 수정
     */
    @EndPointConfig.RequireRole({RoleConstants.ROLE_SUPER_ADMIN})
    @TransactionLogging
    @PostMapping("/modi")
    public ResponseEntity<CommonResponse<RecommendProfileDto>> modiProfile(@RequestBody RecommendProfileDto reqBody) {

        RecommendProfileDto result = recommendProfileService.modiProfile(reqBody);

        return new ResponseEntity<>(new CommonResponse<>(result), HttpStatus.OK);
    }

    /**
     * 프로파일 삭제
     */
    @EndPointConfig.RequireRole({RoleConstants.ROLE_SUPER_ADMIN})
    @TransactionLogging
    @PostMapping("/del")
    public ResponseEntity<CommonResponse<Void>> delProfile(@RequestBody RecommendProfileDto reqBody) {

        recommendProfileService.delProfile(reqBody.getId());

        return new ResponseEntity<>(new CommonResponse<>(), HttpStatus.OK);
    }

    /**
     * 프로파일 목록 조회 (사용중인 프로파일)
     */
    @TransactionLogging
    @GetMapping("")
    public ResponseEntity<CommonResponse<List<RecommendProfileDto>>> getProfileList() {

        List<RecommendProfileDto> result = recommendProfileService.getProfileList();

        return new ResponseEntity<>(new CommonResponse<>(result), HttpStatus.OK);
    }

}