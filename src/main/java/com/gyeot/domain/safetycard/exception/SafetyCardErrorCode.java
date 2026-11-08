package com.gyeot.domain.safetycard.exception;

import com.gyeot.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * D 영역(안심카드·공유·접근 이력)의 에러 코드입니다. (노션 D 명세 'D 에러 코드')
 * 입력값 형식 오류는 여기에 두지 않고 공통 코드 INVALID_INPUT(400)을 사용합니다.
 * 응답의 code에는 enum 이름(예: SAFETY_INFO_NOT_FOUND)이 그대로 내려갑니다.
 */
@Getter                  // httpStatus, message getter 생성 (ErrorCode 인터페이스 구현)
@RequiredArgsConstructor // 아래 final 필드 2개를 채우는 생성자 자동 생성
public enum SafetyCardErrorCode implements ErrorCode {

    // 안심카드 항목
    SAFETY_INFO_INVALID_SELECTION(HttpStatus.BAD_REQUEST, "선택한 안심카드 정보를 확인할 수 없습니다. 다시 선택해 주세요."), // C 요청 생성·수정 (공개 항목 ID 검증)
    SAFETY_INFO_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 안심카드 항목입니다."),                                   // 항목 수정·삭제
    SAFETY_INFO_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 등록된 항목입니다. 기존 항목을 수정해 주세요."),                  // 항목 작성 (1개만 등록하는 항목)

    // 공유
    SHARED_INFO_NOT_FOUND(HttpStatus.NOT_FOUND, "전달받은 정보를 찾을 수 없습니다."),        // 전달받은 정보 조회 (이 요청과 무관한 사람)
    SHARING_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 공유입니다."),                    // 공유 직접 철회·다시 허용
    SHARING_REALLOW_NOT_ALLOWED(HttpStatus.CONFLICT, "다시 허용할 수 없는 공유입니다."),     // 공유 다시 허용 (요청이 끝났거나 담당자가 바뀜)
    SHARING_NOTHING_TO_REALLOW(HttpStatus.CONFLICT, "다시 허용할 항목이 없습니다.");         // 공유 다시 허용 (철회한 항목이 모두 삭제됨)

    private final HttpStatus httpStatus; // 응답 HTTP 상태
    private final String message;        // 응답 message (사용자에게 보이는 문구)

    @Override
    public String getCode() {
        return name();
    }
}
