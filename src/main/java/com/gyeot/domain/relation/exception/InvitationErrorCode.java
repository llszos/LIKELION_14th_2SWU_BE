package com.gyeot.domain.relation.exception;

import com.gyeot.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 나의 사람·보호자 초대 처리에서 사용하는 오류입니다. */
@Getter
@RequiredArgsConstructor
public enum InvitationErrorCode implements ErrorCode {

    INVITATION_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 초대입니다."),
    INVITATION_NOT_PENDING(HttpStatus.CONFLICT, "대기 중인 초대만 취소할 수 있습니다.");

    private final HttpStatus httpStatus;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}