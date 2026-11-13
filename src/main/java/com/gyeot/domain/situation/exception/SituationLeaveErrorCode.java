package com.gyeot.domain.situation.exception;

import com.gyeot.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum SituationLeaveErrorCode implements ErrorCode {
    SITUATION_ENDED(HttpStatus.CONFLICT, "이미 종료된 상황입니다.");

    private final HttpStatus httpStatus;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
