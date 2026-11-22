package com.gyeot.domain.situation.exception;

import com.gyeot.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum SituationErrorCode implements ErrorCode {
    SITUATION_NOT_FOUND(HttpStatus.NOT_FOUND, "상황을 찾을 수 없습니다."),
    SITUATION_FORBIDDEN(HttpStatus.FORBIDDEN, "상황을 종료할 권한이 없습니다."),
    SITUATION_ALREADY_ENDED(HttpStatus.CONFLICT, "이미 종료된 상황입니다.");

    private final HttpStatus httpStatus;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
