package com.gyeot.domain.situation.exception;

import com.gyeot.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/** 명세의 기존 코드만 사용합니다. #94의 SituationErrorCode와는 병합 후 통합 여부를 검토합니다. */
public enum SituationHistoryErrorCode implements ErrorCode {
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
