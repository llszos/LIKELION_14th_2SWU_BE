package com.gyeot.domain.situation.exception;

import com.gyeot.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum SituationRemovalErrorCode implements ErrorCode {
    SITUATION_NOT_FOUND(HttpStatus.NOT_FOUND, "상황을 찾을 수 없습니다."),
    SITUATION_PARTICIPANT_NOT_FOUND(HttpStatus.NOT_FOUND, "참여자를 찾을 수 없습니다."),
    SITUATION_FORBIDDEN(HttpStatus.FORBIDDEN, "참여자를 제외할 권한이 없습니다."),
    SITUATION_OWNER_REMOVAL_NOT_ALLOWED(HttpStatus.CONFLICT, "상황 생성자는 제외할 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String message;
    @Override public String getCode() { return name(); }
}
