package com.gyeot.domain.carerequest.service;

import com.gyeot.global.exception.BusinessException;

import java.time.LocalDateTime;

import static com.gyeot.domain.carerequest.exception.CareRequestErrorCode.REQUEST_DEADLINE_INVALID;

/**
 * 대상자 응답 기한 규칙 (F-05)
 * 요청 생성(#69)과 다시 부탁하기(#71)가 같은 규칙을 쓰도록 모았습니다.
 *
 * - 요청자가 고른 시간: 1~72시간, 보내지 않으면 24시간 → 범위 밖이면 400 REQUEST_DEADLINE_INVALID
 * - 실제 기한 = min(지금 + 고른 시간, 시작 시각). 시작까지 남은 시간보다 길면 에러 없이 시작 시각으로 제한
 */
public final class ResponseDeadlinePolicy {

    public static final int DEFAULT_HOURS = 24;
    public static final int MIN_HOURS = 1;
    public static final int MAX_HOURS = 72; // 3일

    private ResponseDeadlinePolicy() {
    }

    /** 보내지 않았으면 24시간, 1~72 밖이면 400 */
    public static int resolveHours(Integer requested) {
        if (requested == null) {
            return DEFAULT_HOURS;
        }
        if (requested < MIN_HOURS || requested > MAX_HOURS) {
            throw new BusinessException(REQUEST_DEADLINE_INVALID);
        }
        return requested;
    }

    /**
     * 응답 기한 = min(지금 + 시간, 시작 시각)
     * startsAt이 null(급한 요청)이면 제한하지 않습니다.
     */
    public static LocalDateTime deadline(LocalDateTime now, int hours, LocalDateTime startsAt) {
        LocalDateTime deadline = now.plusHours(hours);
        return startsAt != null && deadline.isAfter(startsAt) ? startsAt : deadline;
    }
}
