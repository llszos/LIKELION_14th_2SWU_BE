package com.gyeot.global.exception;

import org.springframework.http.HttpStatus;

/** 각 도메인의 에러 코드 enum이 구현하는 공통 약속입니다. */
public interface ErrorCode {
    HttpStatus getHttpStatus();
    String getCode();
    String getMessage();
}
