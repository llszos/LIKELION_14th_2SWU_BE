package com.gyeot.domain.relation.exception;

import com.gyeot.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 비상연락 대상 관리에서 사용하는 오류입니다. */
@Getter
@RequiredArgsConstructor
public enum EmergencyContactErrorCode implements ErrorCode {

    CONTACT_ORDER_CHANGED(HttpStatus.CONFLICT, "연락 대상 목록이 변경되었습니다. 최신 목록을 조회해 주세요."),
    EMERGENCY_CONTACT_USER_NOT_AVAILABLE(HttpStatus.CONFLICT, "비상연락 대상에 등록할 수 없는 계정입니다.");

    private final HttpStatus httpStatus;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
