package com.gyeot.domain.carerequest.service;

import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.situation.query.SituationParticipantReader;
import com.gyeot.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

import static com.gyeot.domain.carerequest.exception.CareRequestErrorCode.REQUEST_SCHEDULE_INVALID;
import static com.gyeot.domain.carerequest.exception.CareRequestErrorCode.REQUEST_START_TOO_SOON;

/**
 * 요청 일정 규칙을 한곳에서 확인합니다. (F-05, F-29)
 * 요청 수정(#73)과 요청 생성(#69)이 같은 규칙과 같은 에러 코드를 쓰도록 모았습니다.
 *
 * - 시작은 지금부터 1시간 이후 → 아니면 400 REQUEST_START_TOO_SOON
 * - 시작 < 종료, 시작·종료가 상황 기간 안 → 아니면 400 REQUEST_SCHEDULE_INVALID
 *   상황 기간은 날짜 단위라 "시작일 0시 ~ 종료일 끝(다음 날 0시)"으로 봅니다.
 */
@Component
@RequiredArgsConstructor
public class RequestScheduleValidator {
    private final SituationParticipantReader situationReader; // B: 상황 기간 조회

    /** 시작이 지금부터 1시간 이후인지 (엔티티와 같은 기준 CareRequest.MIN_START_LEAD_TIME) */
    public void requireStartLeadTime(LocalDateTime startsAt, LocalDateTime now) {
        if (startsAt.isBefore(now.plus(CareRequest.MIN_START_LEAD_TIME))) {
            throw new BusinessException(REQUEST_START_TOO_SOON);
        }
    }

    /** 시작 < 종료이고, 둘 다 상황 기간 안인지 */
    public void requireWithinSituation(Long situationId, LocalDateTime startsAt, LocalDateTime endsAt) {
        if (!startsAt.isBefore(endsAt)) {
            throw new BusinessException(REQUEST_SCHEDULE_INVALID);
        }
        SituationParticipantReader.SituationSummary situation = situationReader.findSituation(situationId)
                .orElseThrow(() -> new BusinessException(REQUEST_SCHEDULE_INVALID));

        LocalDateTime periodStart = situation.startDate().atStartOfDay();
        LocalDateTime periodEnd = situation.endDate().plusDays(1).atStartOfDay(); // 종료일 끝까지 포함
        if (startsAt.isBefore(periodStart) || endsAt.isAfter(periodEnd)) {
            throw new BusinessException(REQUEST_SCHEDULE_INVALID);
        }
    }
}
