package com.gyeot.domain.situation.exception;

import com.gyeot.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/** 상황의 존재 여부와 접근 불가를 동일한 오류로 처리합니다. */
public enum SituationQueryErrorCode implements ErrorCode {
    SITUATION_NOT_FOUND;

    @Override
    public HttpStatus getHttpStatus() {
        return HttpStatus.NOT_FOUND;
    }

    @Override
    public String getCode() {
        return name();
    }

    @Override
    public String getMessage() {
        return "상황을 찾을 수 없습니다.";
    }
}
