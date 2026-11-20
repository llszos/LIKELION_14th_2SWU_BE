package com.gyeot.domain.relation.exception;

import com.gyeot.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 관계 해제에서 사용하는 오류입니다. */
@Getter
@RequiredArgsConstructor
public enum RelationshipErrorCode implements ErrorCode {

    RELATIONSHIP_NOT_FOUND(HttpStatus.NOT_FOUND, "활성 관계를 찾을 수 없습니다."),
    RELATIONSHIP_ALREADY_ENDED(HttpStatus.CONFLICT, "이미 종료된 관계입니다.");

    private final HttpStatus httpStatus;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
