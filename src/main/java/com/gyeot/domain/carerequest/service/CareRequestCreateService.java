package com.gyeot.domain.carerequest.service;

import com.gyeot.domain.carerequest.dto.request.CreateCareRequestRequest;
import com.gyeot.domain.carerequest.dto.response.CreateCareRequestResponse;
import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.event.CareRequestCreatedEvent;
import com.gyeot.domain.carerequest.repository.CareRequestRepository;
import com.gyeot.domain.carerequest.repository.RequestAssignmentRepository;
import com.gyeot.domain.safetycard.query.SafetyInformationSharingReader;
import com.gyeot.domain.situation.query.SituationParticipantReader;
import com.gyeot.domain.situation.query.SituationParticipantReader.SituationSummary;
import com.gyeot.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

import static com.gyeot.domain.carerequest.exception.CareRequestErrorCode.*;

/**
 * 도움 요청 생성을 처리합니다. (#69, F-05, F-03)
 * POST /api/care-requests
 *
 * 처리 순서
 * 1. 입력 확인: 응답 기한 1~72시간(400 REQUEST_DEADLINE_INVALID), 장소 100자(400 REQUEST_PLACE_TOO_LONG),
 *    부탁 내용 500자(400 REQUEST_DESCRIPTION_TOO_LONG)
 * 2. 상황 확인: 내가 만든 상황인지(아니거나 없으면 400 REQUEST_SITUATION_INVALID), 진행 중인지(종료 409 REQUEST_SITUATION_ENDED)
 * 3. 대상 확인: 본인 아님 + 상황 참여자 + 나의 사람·서로의 보호자 (아니면 400 REQUEST_TARGET_INVALID)
 * 4. 일정 확인: 시작은 지금부터 1시간 이후(400 REQUEST_START_TOO_SOON), 시작 < 종료이고 상황 기간 안(400 REQUEST_SCHEDULE_INVALID)
 * 5. 공개 항목 확인: D가 검증 (400 SAFETY_INFO_INVALID_SELECTION, 예외를 그대로 둠)
 * 6. 요청과 최초 지정 저장 → 생성 이벤트 발행 (D가 같은 트랜잭션에서 공유 예정 권한 생성)
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CareRequestCreateService {

    static final int DEFAULT_RESPONSE_DEADLINE_HOURS = 24; // 응답 기한 기본값 (F-05)
    static final int MIN_RESPONSE_DEADLINE_HOURS = 1;
    static final int MAX_RESPONSE_DEADLINE_HOURS = 72;     // 3일
    private static final String SITUATION_ENDED = "ENDED"; // B SituationSummary.status (문자열 계약)

    private final CareRequestRepository careRequestRepository;
    private final RequestAssignmentRepository assignmentRepository;
    private final SituationParticipantReader situationReader;   // B: 상황 생성자·기간·상태
    private final RequestTargetEligibility targetEligibility;   // B 참여자 + A 관계
    private final RequestScheduleValidator scheduleValidator;
    private final SafetyInformationSharingReader sharingReader; // D: 공개 항목 검증
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public CreateCareRequestResponse create(Long requesterId, CreateCareRequestRequest body) {
        LocalDateTime now = LocalDateTime.now(clock);

        // 1. 입력 확인 (다른 영역을 부르기 전에 입력만으로 판단할 수 있는 것부터)
        int deadlineHours = resolveDeadlineHours(body.responseDeadlineHours());
        String place = body.place().strip(); // 공백만 있는 값은 DTO에서 막음
        if (place.length() > CareRequest.MAX_PLACE_LENGTH) {
            throw new BusinessException(REQUEST_PLACE_TOO_LONG);
        }
        String description = normalizeDescription(body.description());

        // 2. 상황 확인
        SituationSummary situation = getMyActiveSituation(body.situationId(), requesterId);

        // 3. 대상 확인
        if (!targetEligibility.isEligible(body.situationId(), requesterId, body.targetUserId())) {
            throw new BusinessException(REQUEST_TARGET_INVALID);
        }

        // 4. 일정 확인
        scheduleValidator.requireStartLeadTime(body.startsAt(), now);
        scheduleValidator.requireWithinSituation(situation, body.startsAt(), body.endsAt());

        // 5. 공개 항목 확인 (D)
        sharingReader.validateSelection(requesterId, body.informationIds());

        // 6. 저장
        CareRequest request = careRequestRepository.save(CareRequest.createGeneral(body.situationId(), requesterId,
                body.helpType(), description, place, body.startsAt(), body.endsAt(), null));
        RequestAssignment assignment = assignmentRepository.save(RequestAssignment.assign(request,
                body.targetUserId(), 1, responseDeadline(now, deadlineHours, body.startsAt()),
                body.responseDeadlineHours())); // 고른 값 그대로 저장 (null이면 기본값 규칙, N-07 복제용)

        // D: 최초 지정에 공유 예정(PENDING) 권한 생성 / B: 대상자에게 요청 알림
        eventPublisher.publishEvent(CareRequestCreatedEvent.of(assignment, body.informationIds(), now));
        return CreateCareRequestResponse.of(request, assignment);
    }

    // === 1. 입력 확인 ===

    /** 보내지 않았으면 24시간, 1~72 밖이면 400 */
    private int resolveDeadlineHours(Integer requested) {
        if (requested == null) {
            return DEFAULT_RESPONSE_DEADLINE_HOURS;
        }
        if (requested < MIN_RESPONSE_DEADLINE_HOURS || requested > MAX_RESPONSE_DEADLINE_HOURS) {
            throw new BusinessException(REQUEST_DEADLINE_INVALID);
        }
        return requested;
    }

    /** 부탁 내용은 선택 입력: 앞뒤 공백을 빼고, 비어 있으면 null (공백 포함 500자) */
    private String normalizeDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        String stripped = description.strip();
        if (stripped.length() > CareRequest.MAX_DESCRIPTION_LENGTH) {
            throw new BusinessException(REQUEST_DESCRIPTION_TOO_LONG);
        }
        return stripped;
    }

    // === 2. 상황 확인 ===

    /**
     * 다른 사람의 상황은 종료 여부를 알려 주지 않도록, 내 상황인지를 먼저 확인합니다.
     * (없는 상황과 다른 사람 상황은 같은 400 REQUEST_SITUATION_INVALID)
     */
    private SituationSummary getMyActiveSituation(Long situationId, Long requesterId) {
        SituationSummary situation = situationReader.findSituation(situationId)
                .filter(found -> found.creatorId().equals(requesterId))
                .orElseThrow(() -> new BusinessException(REQUEST_SITUATION_INVALID));
        if (SITUATION_ENDED.equals(situation.status())) {
            throw new BusinessException(REQUEST_SITUATION_ENDED);
        }
        return situation;
    }

    // === 6. 저장 ===

    /**
     * 응답 기한 = min(지금 + 고른 시간, 시작 시각)
     * 시작까지 남은 시간보다 길면 에러 없이 시작 시각으로 제한합니다. (F-05)
     * 시작은 지금부터 1시간 이후라 기한도 항상 지금부터 1시간 이후입니다.
     */
    private LocalDateTime responseDeadline(LocalDateTime now, int hours, LocalDateTime startsAt) {
        LocalDateTime deadline = now.plusHours(hours);
        return deadline.isAfter(startsAt) ? startsAt : deadline;
    }
}
