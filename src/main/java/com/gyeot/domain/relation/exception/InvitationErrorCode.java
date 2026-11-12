package com.gyeot.domain.relation.exception;

import com.gyeot.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 나의 사람·보호자 초대 처리에서 사용하는 오류입니다. */
@Getter
@RequiredArgsConstructor
public enum InvitationErrorCode implements ErrorCode {

    INVITATION_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 초대입니다."),
    INVITATION_NOT_PENDING(HttpStatus.CONFLICT, "대기 중인 초대만 처리할 수 있습니다."),
    INVITATION_EXPIRED(HttpStatus.CONFLICT, "만료된 초대 링크입니다."),
    INVITATION_SELF_RESPONSE(HttpStatus.CONFLICT, "본인이 보낸 초대에는 응답할 수 없습니다."),
    INVITATION_NOT_ACCEPTED(HttpStatus.CONFLICT, "수락된 초대에서만 관계를 추가할 수 있습니다."),
    INVITATION_RESPONDER_ONLY(HttpStatus.FORBIDDEN, "초대를 수락한 사용자만 관계를 추가할 수 있습니다."),
    RELATIONSHIP_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 나의 사람으로 등록되어 있습니다."),
    GUARDIAN_RELATIONSHIP_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 서로의 보호자로 등록되어 있습니다."),
    GUARDIAN_LIMIT_REACHED(HttpStatus.CONFLICT, "보호자는 최대 3명까지 등록할 수 있어요."),
    INVITATION_USER_NOT_AVAILABLE(HttpStatus.CONFLICT, "초대에 참여할 수 없는 계정입니다.");

    private final HttpStatus httpStatus;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
