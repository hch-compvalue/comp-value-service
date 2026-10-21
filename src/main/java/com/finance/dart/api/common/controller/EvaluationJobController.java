package com.finance.dart.api.common.controller;

import com.finance.dart.api.common.dto.evaluation.EvaluationJobDto;
import com.finance.dart.api.common.dto.evaluation.StockEvaluationRequest;
import com.finance.dart.api.common.service.EvaluationJobService;
import com.finance.dart.common.dto.CommonResponse;
import com.finance.dart.common.exception.BizException;
import com.finance.dart.common.logging.TransactionLogging;
import com.finance.dart.member.dto.Member;
import com.finance.dart.member.service.MemberService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 일괄 평가 작업 (서버 백그라운드 실행) — 시작 / 현재 상태·결과 / 중지
 * - 로그인 회원 단위로 작업 1개
 */
@RestController
@RequestMapping("main/evaluate/jobs")
@RequiredArgsConstructor
public class EvaluationJobController {

    private final EvaluationJobService evaluationJobService;
    private final MemberService memberService;

    @TransactionLogging
    @PostMapping
    public ResponseEntity<CommonResponse<EvaluationJobDto>> start(HttpServletRequest request,
                                                                  @RequestBody StockEvaluationRequest body) {
        return ResponseEntity.ok(new CommonResponse<>(evaluationJobService.start(loginMemberId(request), body.getSymbols())));
    }

    /** 현재(또는 마지막) 작업 — from: 이미 받은 결과 수. 작업이 없으면 response=null */
    @GetMapping("/current")
    public ResponseEntity<CommonResponse<EvaluationJobDto>> current(HttpServletRequest request,
                                                                    @RequestParam(value = "from", defaultValue = "0") int from) {
        return ResponseEntity.ok(new CommonResponse<>(evaluationJobService.getCurrent(loginMemberId(request), from)));
    }

    @TransactionLogging
    @PostMapping("/current/cancel")
    public ResponseEntity<CommonResponse<EvaluationJobDto>> cancel(HttpServletRequest request) {
        return ResponseEntity.ok(new CommonResponse<>(evaluationJobService.cancel(loginMemberId(request))));
    }

    private Long loginMemberId(HttpServletRequest request) {
        Member member = memberService.getLoginMember(request);
        if (member == null) {
            throw new BizException("로그인이 필요합니다.");
        }
        return member.getId();
    }
}
