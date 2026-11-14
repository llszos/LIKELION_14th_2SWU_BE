package com.gyeot.domain.carerequest.exception;

import com.gyeot.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * C 영역 (도움 요청)의 에러 코드입니다. (API 명세 'C 에러 코드' 표 참조)
 * 입력값 형식 오류(필수값 누락, 타입 불일치 등)는 여기에 두지 않고 공통 코드 INVALID_INPUT(400)을 사용합니다.
 * 응답의 code에는 enum 이름이 그대로 내려갑니다.
 * 응답 message 문구는 와이어프레임을 기준으로 작성했고, 와이어프레임에 없는 경우는 제가 임의로 작성했습니다.
 * 해당 Enum은 DB 컬럼이 아니라서 PR #83 (D 코드) 요구사항을 반영하지 않았습니다.
 */
@Getter
@RequiredArgsConstructor
public enum CareRequestErrorCode implements ErrorCode {
    // 404 Not Found
    REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 요청입니다."),
    REQUEST_PROPOSAL_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 수정 요청입니다."),

    // 400 Bad Request
    REQUEST_SITUATION_INVALID(HttpStatus.BAD_REQUEST, "선택한 상황에서는 요청을 보낼 수 없습니다."),
    REQUEST_TARGET_INVALID(HttpStatus.BAD_REQUEST, "요청을 보낼 수 없는 대상입니다."),
    REQUEST_START_TOO_SOON(HttpStatus.BAD_REQUEST, "시작 시간은 지금부터 1시간 이후로 정해 주세요."),
    REQUEST_SCHEDULE_INVALID(HttpStatus.BAD_REQUEST, "요청 일정을 다시 확인해 주세요."),
    REQUEST_DEADLINE_INVALID(HttpStatus.BAD_REQUEST, "응답 기한은 1시간에서 3일 사이로 정해 주세요."),
    REQUEST_NO_CHANGES(HttpStatus.BAD_REQUEST, "바뀐 내용이 없습니다."),

    // 409 Conflict
    REQUEST_SITUATION_ENDED(HttpStatus.CONFLICT, "이미 종료된 상황입니다."),
    REQUEST_TARGET_ALREADY_ASSIGNED(HttpStatus.CONFLICT, "이미 부탁했던 사람입니다."),
    REQUEST_NOT_REASSIGNABLE(HttpStatus.CONFLICT, "지금은 다른 사람에게 부탁할 수 없는 요청입니다."),
    REQUEST_ASSIGNMENT_CLOSED(HttpStatus.CONFLICT, "다른 분께 요청이 넘어갔어요."),
    REQUEST_ALREADY_ACCEPTED(HttpStatus.CONFLICT, "이미 수락한 요청입니다."),
    REQUEST_ALREADY_STARTED(HttpStatus.CONFLICT, "이미 시작된 요청입니다."),
    REQUEST_ALREADY_ENDED(HttpStatus.CONFLICT, "이미 끝난 요청입니다."),
    REQUEST_NOT_WITHDRAWABLE(HttpStatus.CONFLICT, "진행 중인 요청은 수락을 철회할 수 없습니다."),
    REQUEST_NOT_STARTED(HttpStatus.CONFLICT, "아직 시작하지 않은 요청입니다."),
    REQUEST_NOT_EDITABLE(HttpStatus.CONFLICT, "진행 중인 요청은 수정할 수 없습니다."),
    REQUEST_PROPOSAL_NO_TIME(HttpStatus.CONFLICT, "시작이 가까워 수정 내용을 확인받을 시간이 없습니다."),
    REQUEST_PROPOSAL_NOT_PENDING(HttpStatus.CONFLICT, "이미 처리된 수정 요청입니다."),
    REQUEST_PROPOSAL_EXPIRED(HttpStatus.CONFLICT, "확인 기한이 지나 수정 내용이 반영되지 않았습니다.");

    private final HttpStatus httpStatus; // 응답 HTTP 상태
    private final String message; // 응답 message (사용자에게 보이는 문구)

    @Override
    public String getCode() {
        return name();
    }
}
