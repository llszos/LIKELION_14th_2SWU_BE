package com.gyeot.domain.situation.exception;

import com.gyeot.global.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/** 기능 미완성을 유효한 생성 입력 오류나 성공으로 위장하지 않습니다. 공개 오류는 팀 확인 필요. */
public enum SituationCreationErrorCode implements ErrorCode {
    SITUATION_TYPE_NOT_CONFIGURED;
    @Override public HttpStatus getHttpStatus() { return HttpStatus.SERVICE_UNAVAILABLE; }
    @Override public String getCode() { return name(); }
    @Override public String getMessage() { return "상황 종류 및 기타 문구 저장 계약이 아직 준비되지 않았습니다."; }
}
