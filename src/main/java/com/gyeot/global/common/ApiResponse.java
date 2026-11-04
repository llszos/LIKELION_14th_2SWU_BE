package com.gyeot.global.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gyeot.global.exception.ErrorCode;

/** 모든 JSON API에서 사용하는 공통 응답. HTTP 상태는 Controller 또는 예외 처리기가 지정합니다. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiResponse<T>(String status, String code, String message, T data) {

    public static <T> ApiResponse<T> success(T data) {
        return success(data, null);
    }

    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>("success", null, message, data);
    }

    public static ApiResponse<Void> success() {
        return success(null);
    }

    public static ApiResponse<Void> error(ErrorCode errorCode) {
        return new ApiResponse<>("error", errorCode.getCode(), errorCode.getMessage(), null);
    }
}
