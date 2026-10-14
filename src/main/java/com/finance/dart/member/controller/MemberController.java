package com.finance.dart.member.controller;


import com.finance.dart.api.common.constants.RequestContextConst;
import com.finance.dart.api.common.context.RequestContext;
import com.finance.dart.common.config.EndPointConfig;
import com.finance.dart.common.constant.ResponseEnum;
import com.finance.dart.common.dto.CommonResponse;
import com.finance.dart.common.logging.TransactionLogging;
import com.finance.dart.common.util.StringUtil;
import com.finance.dart.member.dto.*;
import com.finance.dart.member.enums.RoleConstants;
import com.finance.dart.member.service.MemberService;
import com.finance.dart.member.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Slf4j
@AllArgsConstructor
@RequestMapping("member")
@RestController
public class MemberController {

    private final RequestContext requestContext;
    private final SessionService sessionService;
    private final MemberService memberService;

    /**
     * 로그인
     * @param loginDTO
     * @return "" 응답은 로그인 실패
     */
    @EndPointConfig.PublicEndpoint
    @TransactionLogging
    @PostMapping("/login")
    public ResponseEntity<CommonResponse<LoginDTO>> login(@Valid @RequestBody LoginDTO loginDTO) {

        CommonResponse<LoginDTO> response = sessionService.login(loginDTO);

        if(response != null && response.getResponse() != null) {
            // 쿠키 설정
            ResponseCookie cookie = sessionService.createSessionCookie(
                    requestContext.getAttributeAsString(RequestContextConst.SESSION_ID_UUID));
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, cookie.toString())
                    .body(response);
        }

        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /**
     * 로그아웃
     * @param request
     * @return
     */
    @TransactionLogging
    @DeleteMapping("/logout")
    public ResponseEntity<CommonResponse> logout(HttpServletRequest request) {

        String sessionId = sessionService.logout(request);

        ResponseCookie cookie = sessionService.deleteSessionCookie(sessionId);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new CommonResponse<>(ResponseEnum.OK));
    }

    /**
     * 회원가입
     * @param memberJoinReqDto
     * @return
     */
    @EndPointConfig.PublicEndpoint
    @TransactionLogging
    @PostMapping("/join")
    public ResponseEntity<CommonResponse<Member>> join(@Valid @RequestBody MemberJoinReqDto memberJoinReqDto) {

        CommonResponse<Member> response = memberService.join(memberJoinReqDto);

        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /**
     * 세션 keep-alive (TTL 갱신 전용)
     * - SSE 전용 페이지 등에서 세션 만료를 방지하기 위한 경량 엔드포인트
     * - SessionCookieRefreshAdvice가 자동으로 Redis TTL + 쿠키를 갱신
     * - 응답에 sessionTTL(초)을 포함하여 프론트엔드 세션 타이머 동기화
     */
    @TransactionLogging
    @GetMapping("/session/keepalive")
    public ResponseEntity<CommonResponse> sessionKeepAlive(HttpServletRequest request) {
        String sessionId = sessionService.getSessionId(request);
        Long ttl = (sessionId != null) ? sessionService.getLoginSessionTTL(sessionId) : null;
        Map<String, Object> body = new HashMap<>();
        if (ttl != null && ttl > 0) body.put("sessionTTL", ttl);
        return new ResponseEntity<>(new CommonResponse<>(body), HttpStatus.OK);
    }

    /**
     * <pre>
     * 로그인 여부 체크
     * </pre>
     * @return
     */
    @EndPointConfig.PublicEndpoint
    @TransactionLogging
    @GetMapping("/me")
    public ResponseEntity<CommonResponse> me(HttpServletRequest request) {

        CommonResponse response = null;
        if(sessionService.sessionCheck(request)) {
            response = new CommonResponse(ResponseEnum.OK);
        } else {
            response = new CommonResponse(ResponseEnum.LOGIN_SESSION_EXPIRED);
        }

        return new ResponseEntity<>(response, ResponseEnum.OK.getHttpStatus());
    }

    /**
     * <pre>
     * 현재 로그인한 회원 정보 조회
     * </pre>
     * @param request
     * @return
     */
    @TransactionLogging
    @GetMapping("/me/info")
    public ResponseEntity<CommonResponse> getLoginMember(HttpServletRequest request) {

        Member member = memberService.getLoginMember(request);
        CommonResponse response = new CommonResponse(member);

        return new ResponseEntity<>(response, ResponseEnum.OK.getHttpStatus());
    }

    /**
     * 회원정보 수정
     * @param request
     * @param reqBody
     * @return
     */
    @TransactionLogging
    @PostMapping("/update")
    public ResponseEntity<CommonResponse<Member>> updateMember(HttpServletRequest request, @RequestBody Member reqBody) {

        CommonResponse<Member> response = memberService.updateMember(request, reqBody);

        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /**
     * 회원정보 등록승인여부 수정
     * @param request
     * @param reqBody
     * @return
     */
    @EndPointConfig.RequireRole({RoleConstants.ROLE_ADMIN, RoleConstants.ROLE_SUPER_ADMIN})
    @TransactionLogging
    @PostMapping("/update/approval")
    public ResponseEntity<CommonResponse<MemberApproval>> updateMemberApproval(HttpServletRequest request, @RequestBody MemberApproval reqBody) {

        CommonResponse<MemberApproval> response = memberService.updateMemberApproval(reqBody);

        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /**
     * 비밀번호 변경
     * @param request
     * @param reqBody
     * @return
     */
    @TransactionLogging
    @PostMapping("/password")
    public ResponseEntity<CommonResponse<Void>> changePassword(HttpServletRequest request, @Valid @RequestBody PasswordChangeDto reqBody) {

        CommonResponse<Void> response = memberService.changePassword(request, reqBody.getCurrentPassword(), reqBody.getNewPassword());

        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /**
     * 회원 탈퇴
     * @param request
     * @param reqBody
     * @return
     */
    @TransactionLogging
    @PostMapping("/delete")
    public ResponseEntity<CommonResponse<Void>> deleteMember(HttpServletRequest request, @Valid @RequestBody MemberDeleteDto reqBody) {

        CommonResponse<Void> response = memberService.deleteMember(request, reqBody.getPassword());

        // 세션 쿠키 삭제
        String sessionId = sessionService.getSessionId(request);
        ResponseCookie cookie = sessionService.deleteSessionCookie(sessionId);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(response);
    }

    /**
     * 회원 목록 조회 (페이징, 검색)
     * - 관리자 또는 슈퍼관리자 권한 필요
     * @param httpRequest
     * @param request
     * @return
     */
    @EndPointConfig.RequireRole({RoleConstants.ROLE_ADMIN, RoleConstants.ROLE_SUPER_ADMIN})
    @TransactionLogging
    @PostMapping("/list")
    public ResponseEntity<CommonResponse<MemberListResponseDto>> getMemberList(
            HttpServletRequest httpRequest,
            @Valid @RequestBody MemberListRequestDto request) {

        CommonResponse<MemberListResponseDto> response = memberService.getMemberList(request);

        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /**
     * email 로 userName 목록 조회
     * @param request
     * @return
     */
    @EndPointConfig.PublicEndpoint
    @TransactionLogging
    @PostMapping("/find-usernames")
    public ResponseEntity<CommonResponse<List<String>>> findUserNamesByEmail(@RequestBody Member request) {

        List<String> response = new ArrayList<>();

        if(!StringUtil.isStringEmpty(request.getEmail())) {
            response = memberService.findUsernamesByEmail(request.getEmail());
        }

        return new ResponseEntity<>(new CommonResponse<>(response), HttpStatus.OK);
    }

    /**
     * 비밀번호 재설정 요청 (인증코드 발송)
     * @param reqBody
     * @return
     */
    @EndPointConfig.PublicEndpoint
    @TransactionLogging
    @PostMapping("/password/reset/request")
    public ResponseEntity<CommonResponse<PasswordResetRequestResponseDto>> resetPasswordRequest(@Valid @RequestBody PasswordResetRequestDto reqBody) {

        CommonResponse<PasswordResetRequestResponseDto> response = memberService.resetPasswordBeforeByEmail(
                reqBody.getUsername(),
                reqBody.getEmail()
        );

        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /**
     * 비밀번호 재설정 인증 및 임시 비밀번호 발급
     * @param reqBody
     * @return
     */
    @EndPointConfig.PublicEndpoint
    @TransactionLogging
    @PostMapping("/password/reset/verify")
    public ResponseEntity<CommonResponse<PasswordResetResponseDto>> resetPasswordVerify(@Valid @RequestBody PasswordResetVerifyDto reqBody) {

        CommonResponse<PasswordResetResponseDto> response = memberService.resetPasswordAfterByEmail(
                reqBody.getUsername(),
                reqBody.getEmail(),
                reqBody.getVerificationCode()
        );

        return new ResponseEntity<>(response, HttpStatus.OK);
    }




    /** =================== 관리자 =================== **/

    /**
     * 관리자용 회원정보 수정
     * - 다른 회원의 정보를 수정하는 기능 (관리자 또는 슈퍼관리자만 가능)
     * @param httpRequest
     * @param reqBody
     * @return
     */
    @EndPointConfig.RequireRole({RoleConstants.ROLE_ADMIN, RoleConstants.ROLE_SUPER_ADMIN})
    @TransactionLogging
    @PostMapping("/admin/update")
    public ResponseEntity<CommonResponse<Member>> updateMemberByAdmin(
            HttpServletRequest httpRequest,
            @Valid @RequestBody AdminMemberUpdateDto reqBody) {

        CommonResponse<Member> response = memberService.updateMemberByAdmin(
                reqBody.getMemberId(),
                reqBody.getEmail(),
                reqBody.getNickname()
        );

        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /**
     * 관리자용 회원 탈퇴 (슈퍼관리자만 가능)
     * - 다른 회원을 탈퇴시키는 기능
     * @param httpRequest
     * @param reqBody
     * @return
     */
    @EndPointConfig.RequireRole({RoleConstants.ROLE_SUPER_ADMIN})
    @TransactionLogging
    @PostMapping("/admin/delete")
    public ResponseEntity<CommonResponse<Void>> deleteMemberByAdmin(
            HttpServletRequest httpRequest,
            @Valid @RequestBody AdminMemberDeleteDto reqBody) {

        CommonResponse<Void> response = memberService.deleteMemberByAdmin(reqBody.getMemberId());

        return new ResponseEntity<>(response, HttpStatus.OK);
    }

}