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
 *   상황 기간은 Asia/Seoul 날짜시각 [startAt, endAt)이며 요청 종료는 endAt과 같아도 됩니다.
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
        requireWithinSituation(situation, startsAt, endsAt);
    }

    /**
     * 이미 조회한 상황으로 확인합니다. (요청 생성 #69: 상황 검증 때 조회한 결과를 다시 쓰려고 B를 한 번만 부름)
     * 규칙은 위 메서드와 같습니다.
     */
    public void requireWithinSituation(SituationParticipantReader.SituationSummary situation,
                                       LocalDateTime startsAt, LocalDateTime endsAt) {
        if (!startsAt.isBefore(endsAt)) {
            throw new BusinessException(REQUEST_SCHEDULE_INVALID);
        }
        LocalDateTime periodStart = situation.startAt();
        LocalDateTime periodEnd = situation.endAt();
        if (startsAt.isBefore(periodStart) || endsAt.isAfter(periodEnd)) {
            throw new BusinessException(REQUEST_SCHEDULE_INVALID);
        }
    }
}
