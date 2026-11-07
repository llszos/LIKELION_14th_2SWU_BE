package com.gyeot.global.exception;

import com.gyeot.global.common.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Controller에서 발생한 예외를 공통 응답으로 변환합니다. */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.getHttpStatus()).body(ApiResponse.error(errorCode));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthenticationException(AuthenticationException exception) {
        return ResponseEntity.status(CommonErrorCode.UNAUTHORIZED.getHttpStatus())
                .body(ApiResponse.error(CommonErrorCode.UNAUTHORIZED));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException(AccessDeniedException exception) {
        return ResponseEntity.status(CommonErrorCode.FORBIDDEN.getHttpStatus())
                .body(ApiResponse.error(CommonErrorCode.FORBIDDEN));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.error(CommonErrorCode.INVALID_INPUT));
    }

    // Spring MVC가 분류한 400, 404, 405, 415 등의 상태와 Allow 같은 응답 헤더는 유지합니다.
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        CommonErrorCode errorCode = statusCode.is5xxServerError()
                ? CommonErrorCode.INTERNAL_SERVER_ERROR : CommonErrorCode.INVALID_INPUT;
        if (statusCode.is5xxServerError()) {
            log.error("Spring MVC 요청 처리 중 서버 오류가 발생했습니다.", exception);
        }
        return super.handleExceptionInternal(exception, ApiResponse.error(errorCode), headers, statusCode, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
        log.error("예상하지 못한 서버 오류가 발생했습니다.", exception);
        return ResponseEntity.internalServerError()
                .body(ApiResponse.error(CommonErrorCode.INTERNAL_SERVER_ERROR));
    }
}
