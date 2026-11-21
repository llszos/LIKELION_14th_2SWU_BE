package com.gyeot.domain.situation.exception;

import com.gyeot.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter @RequiredArgsConstructor
public enum SituationParticipantAddErrorCode implements ErrorCode {
    SITUATION_FORBIDDEN(HttpStatus.FORBIDDEN, "참여자를 추가할 권한이 없습니다."),
    SITUATION_PARTICIPANT_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 참여 중인 사용자입니다.");
    private final HttpStatus httpStatus;
    private final String message;
    @Override public String getCode() { return name(); }
}
