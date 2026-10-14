package com.finance.dart.common.constant;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ResponseEnum {

    // 정상
    OK("20000", "정상 처리되었습니다.", true, HttpStatus.OK),

    // 공통 오류
    INTERNAL_SERVER_ERROR("50000", "서버처리중 오류가 발생했습니다.", false, HttpStatus.INTERNAL_SERVER_ERROR),
    NOT_FOUND("40400", "정보가 존재하지 않습니다.", false, HttpStatus.BAD_REQUEST),
    DUPLICATE("40000", "이미 존재하는 정보입니다.", false, HttpStatus.BAD_REQUEST),

    // 파라미터 오류
    EMPTY_REQ_PARAM("50000", "요청 파라미터 데이터가 존재하지 않습니다.", false, HttpStatus.BAD_REQUEST),

    // 로그인 관련 오류
    LOGIN_NOTFOUND_USER("40400", "user가 존재하지 않습니다.", false, HttpStatus.BAD_REQUEST),
    LOGIN_NOTMATCH_PASSWORD("40000", "비밀번호가 일치하지 않습니다.", false, HttpStatus.BAD_REQUEST),
    LOGIN_SESSION_EXPIRED("40100", "인증정보가 존재하지않습니다.", false, HttpStatus.UNAUTHORIZED),

    // 회원가입 관련 오류
    JOIN_DUPLICATE_USERNAME("40000", "이미 등록된 username 입니다.", false, HttpStatus.BAD_REQUEST),

    // 회원 관련 오류
    MEMBER_NOT_FOUND("40401", "회원 정보를 찾을 수 없습니다.", false, HttpStatus.BAD_REQUEST),
    PASSWORD_NOT_MATCH("40001", "비밀번호가 일치하지 않습니다.", false, HttpStatus.BAD_REQUEST),
    MEMBER_NOT_APPROVED("40300", "회원 승인 대기 중입니다. 관리자 승인 후 이용 가능합니다.", false, HttpStatus.FORBIDDEN),

    // 권한 관련 오류
    FORBIDDEN("40301", "접근 권한이 없습니다.", false, HttpStatus.FORBIDDEN),

    // 메일 전송 오류
    MAIL_SEND_ERR("50000", "메일 전송에 실패했습니다.", false, HttpStatus.INTERNAL_SERVER_ERROR),

    // 인증코드 관련 오류
    VERIFICATION_CODE_EXPIRED("40002", "인증코드가 만료되었습니다. 다시 요청해주세요.", false, HttpStatus.BAD_REQUEST),
    VERIFICATION_CODE_NOT_MATCH("40003", "인증코드가 일치하지 않습니다.", false, HttpStatus.BAD_REQUEST),

    // 스케줄 관리 오류
    SCHEDULE_ALREADY_RUNNING("40900", "이미 실행 중인 스케줄입니다. 끝난 뒤 다시 실행해주세요.", false, HttpStatus.CONFLICT),
    SCHEDULE_INVALID_CRON("40004", "실행 주기(cron) 형식이 올바르지 않습니다.", false, HttpStatus.BAD_REQUEST),
    SCHEDULE_UNKNOWN_JOB("40402", "존재하지 않는 스케줄 작업입니다.", false, HttpStatus.BAD_REQUEST),

    // 게시판 관련 오류 (200 응답)
    BIZ_ERROR("20001", "요청 처리 중 오류가 발생했습니다.", false, HttpStatus.OK),

    ;


    private String code;
    private String message;
    private boolean isSuccess;
    private HttpStatus httpStatus;


    ResponseEnum(String code, String message, boolean isSuccess, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.isSuccess = isSuccess;
        this.httpStatus = httpStatus;
    }
}
