package com.gyeot.domain.carerequest.service;

import com.gyeot.domain.carerequest.dto.request.UpdateCareRequestRequest;
import com.gyeot.domain.carerequest.dto.response.UpdateCareRequestResponse;
import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.entity.RequestChangeEvent;
import com.gyeot.domain.carerequest.entity.RequestChangeProposal;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.ChangeableField;
import com.gyeot.domain.carerequest.enums.InvalidationReason;
import com.gyeot.domain.carerequest.enums.ProposalStatus;
import com.gyeot.domain.carerequest.event.CareRequestChangeConfirmedEvent;
import com.gyeot.domain.carerequest.event.CareRequestChangeProposedEvent;
import com.gyeot.domain.carerequest.repository.RequestAssignmentRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeEventRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeProposalRepository;
import com.gyeot.domain.safetycard.query.SafetyInformationSharingReader;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.gyeot.domain.carerequest.exception.CareRequestErrorCode.*;

/**
 * 요청 수정을 처리합니다. (#73, F-29, F-03)
 * PATCH /api/care-requests/{requestId}
 *
 * 처리 순서
 * 1. 요청 행을 잠그고 요청자인지 확인 (아니면 403, 관련 없으면 Finder가 404)
 * 2. 수정할 수 있는 상태인지 확인 (종료 409 REQUEST_ALREADY_ENDED, 진행 중·시작 시각 지남 409 REQUEST_NOT_EDITABLE)
 * 3. 입력 확인(장소 100자 초과 400 REQUEST_PLACE_TOO_LONG, 공개 항목 D 검증) 후,
 *    보낸 값을 원래 요청 값과 비교해 바뀐 필드를 정함 (공개 항목은 D의 지금 공개 중인 항목과 비교)
 * 4. 바뀐 게 없으면: 대기 중 수정안이 있으면 철회(PROPOSAL_WITHDRAWN), 없으면 400 REQUEST_NO_CHANGES
 * 5. 일정 규칙 확인 (시작을 바꿨을 때만 1시간 규칙, 일정을 바꿨으면 시작 < 종료와 상황 기간)
 * 6. 수락 전이면 바로 반영(APPLIED), 수락 후면 수정안 생성(PROPOSED, 대기 중 수정안은 새 수정안으로 교체)
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CareRequestEditService {

    private final CareRequestFinder careRequestFinder;
    private final RequestAssignmentRepository assignmentRepository;
    private final RequestChangeProposalRepository proposalRepository;
    private final RequestChangeEventRepository eventRepository;
    private final SafetyInformationSharingReader sharingReader; // D: 공개 항목 검증, 지금 공개 중인 항목
    private final RequestScheduleValidator scheduleValidator;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public UpdateCareRequestResponse update(Long requestId, Long userId, UpdateCareRequestRequest body) {
        // 1. 잠그고 조회 + 요청자 확인
        RequestAccess access = careRequestFinder.getAccessibleRequestForUpdate(requestId, userId);
        LocalDateTime now = LocalDateTime.now(clock); // 잠금을 얻은 뒤의 시각
        if (!access.isRequester()) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN); // 요청자만 수정 가능
        }
        CareRequest request = access.request();

        // 2. 수정할 수 있는 상태인지
        validateEditable(request, now);

        // 3. 입력 확인
        // - 장소는 앞뒤 공백을 뺀 뒤 글자 수를 셈 (공백 포함 100자, 넘으면 400 REQUEST_PLACE_TOO_LONG)
        // - 공개 항목을 보냈으면 D에서 검증 (없는 항목, 다른 사람 항목, 삭제된 항목 → 400, 예외를 그대로 둠)
        String place = body.place() == null ? null : body.place().strip();
        if (place != null && place.length() > CareRequest.MAX_PLACE_LENGTH) {
            throw new BusinessException(REQUEST_PLACE_TOO_LONG);
        }
        if (body.informationIds() != null) {
            sharingReader.validateSelection(request.getRequesterId(), body.informationIds());
        }
        Set<ChangeableField> changed = changedFields(request, body, place);

        RequestChangeProposal pending = request.getStatus() == CareRequestStatus.ACCEPTED
                ? proposalRepository.findByCareRequestIdAndStatus(requestId, ProposalStatus.PENDING).orElse(null)
                : null; // 수정안은 수락 후에만 있음

        // 4. 바뀐 게 없을 때
        if (changed.isEmpty()) {
            if (pending == null) {
                throw new BusinessException(REQUEST_NO_CHANGES);
            }
            // 대기 중 수정안을 고쳤더니 원래 요청과 모두 같아짐 → 수정안 철회 (기획 답변)
            invalidate(pending, InvalidationReason.REQUESTER_WITHDRAWN, userId, now);
            return UpdateCareRequestResponse.proposalWithdrawn(requestId);
        }

        // 5. 일정 규칙
        LocalDateTime newStartsAt = changed.contains(ChangeableField.STARTS_AT) ? body.startsAt() : null;
        LocalDateTime newEndsAt = changed.contains(ChangeableField.ENDS_AT) ? body.endsAt() : null;
        validateSchedule(request, changed, newStartsAt, newEndsAt, now);

        String newPlace = changed.contains(ChangeableField.PLACE) ? place : null;
        List<Long> newInformationIds = changed.contains(ChangeableField.INFORMATION_IDS) ? body.informationIds() : null;

        // 6. 수락 전 / 수락 후
        if (request.getStatus() == CareRequestStatus.REQUESTED) {
            return applyBeforeAcceptance(request, changed, newStartsAt, newEndsAt, newPlace, newInformationIds, userId, now);
        }
        return propose(request, pending, changed, newStartsAt, newEndsAt, newPlace, newInformationIds, userId, now);
    }

    // === 2. 상태 확인 ===

    private void validateEditable(CareRequest request, LocalDateTime now) {
        if (request.isTerminal()) {
            throw new BusinessException(REQUEST_ALREADY_ENDED);   // 취소·만료·완료
        }
        if (request.getStatus() == CareRequestStatus.IN_PROGRESS) {
            throw new BusinessException(REQUEST_NOT_EDITABLE);    // 진행 중에는 취소만 가능 (F-29, 기획 확인)
        }
        // 시작 시각이 지났는데 스케줄러가 아직 처리하기 전인 경우도 시작된 것으로 봄 (수락 철회와 같은 기준)
        if (!request.isUrgent() && !now.isBefore(request.getStartsAt())) {
            throw new BusinessException(REQUEST_NOT_EDITABLE);
        }
    }

    // === 3. 바뀐 필드 계산 (원래 요청 값과 비교) ===

    private Set<ChangeableField> changedFields(CareRequest request, UpdateCareRequestRequest body, String place) {
        Set<ChangeableField> changed = EnumSet.noneOf(ChangeableField.class);
        if (body.startsAt() != null && !body.startsAt().equals(request.getStartsAt())) {
            changed.add(ChangeableField.STARTS_AT);
        }
        if (body.endsAt() != null && !body.endsAt().equals(request.getEndsAt())) {
            changed.add(ChangeableField.ENDS_AT);
        }
        if (place != null && !place.equals(request.getPlace())) {
            changed.add(ChangeableField.PLACE);
        }
        if (body.informationIds() != null && informationIdsChanged(request, body.informationIds())) {
            changed.add(ChangeableField.INFORMATION_IDS);
        }
        return changed;
    }

    /** 공개 항목은 순서와 상관없이 집합으로 비교합니다. (지금 공개 중인 항목 = D의 PENDING, ACTIVE 권한) */
    private boolean informationIdsChanged(CareRequest request, List<Long> requested) {
        Set<Long> current = new HashSet<>(sharingReader.findSharingInformationIds(request.getId()));
        return !current.equals(new HashSet<>(requested));
    }

    // === 5. 일정 규칙 ===

    private void validateSchedule(CareRequest request, Set<ChangeableField> changed,
                                  LocalDateTime newStartsAt, LocalDateTime newEndsAt, LocalDateTime now) {
        boolean startChanged = changed.contains(ChangeableField.STARTS_AT);
        boolean endChanged = changed.contains(ChangeableField.ENDS_AT);
        if (!startChanged && !endChanged) {
            return; // 장소·공개 항목만 바꾼 수정
        }
        if (startChanged) {
            scheduleValidator.requireStartLeadTime(newStartsAt, now); // 시작을 바꿨을 때만 1시간 규칙
        }
        LocalDateTime start = startChanged ? newStartsAt : request.getStartsAt();
        LocalDateTime end = endChanged ? newEndsAt : request.getEndsAt();
        scheduleValidator.requireWithinSituation(request.getSituationId(), start, end);
    }

    // === 6-1. 수락 전: 바로 반영 ===

    private UpdateCareRequestResponse applyBeforeAcceptance(CareRequest request, Set<ChangeableField> changed,
                                                            LocalDateTime newStartsAt, LocalDateTime newEndsAt,
                                                            String newPlace, List<Long> newInformationIds,
                                                            Long userId, LocalDateTime now) {
        LocalDateTime previousStartsAt = request.getStartsAt();
        LocalDateTime previousEndsAt = request.getEndsAt();
        RequestAssignment openAssignment = assignmentRepository
                .findByCareRequestIdAndClosedAtIsNull(request.getId()).orElse(null); // 대상자가 빠졌으면 null

        request.editBeforeAcceptance(changed, newStartsAt, newEndsAt, newPlace, now);
        if (newStartsAt != null && openAssignment != null) {
            openAssignment.limitDeadlineTo(newStartsAt); // 시작이 앞당겨지면 응답 기한도 새 시작 시각까지로 줄어듦
        }

        eventRepository.save(RequestChangeEvent.requestUpdated(request, changed, previousStartsAt, previousEndsAt,
                userId, now));
        // D: 공개 항목이 바뀌었으면 대상자의 공유 예정(PENDING) 권한 반영 / B: 대상자에게 수정 알림
        eventPublisher.publishEvent(CareRequestChangeConfirmedEvent.ofDirectEdit(
                request, openAssignment, changed, newInformationIds, now));
        return UpdateCareRequestResponse.applied(request.getId());
    }

    // === 6-2. 수락 후: 수정안 생성 (대기 중이면 교체) ===

    private UpdateCareRequestResponse propose(CareRequest request, RequestChangeProposal pending,
                                              Set<ChangeableField> changed,
                                              LocalDateTime newStartsAt, LocalDateTime newEndsAt,
                                              String newPlace, List<Long> newInformationIds,
                                              Long userId, LocalDateTime now) {
        requireProposalTime(request, newStartsAt, now);

        if (pending != null) {
            // 기존 수정안을 먼저 무효로 바꾸고 DB에 반영한 뒤 새 수정안을 저장합니다.
            // Hibernate는 INSERT를 UPDATE보다 먼저 실행해서, 순서를 지키지 않으면 요청당 PENDING 1건 제약에 걸림
            invalidate(pending, InvalidationReason.REPLACED_BY_NEW_PROPOSAL, userId, now);
            proposalRepository.saveAndFlush(pending);
        }

        RequestAssignment assignee = assignmentRepository.findByCareRequestIdAndClosedAtIsNull(request.getId())
                .filter(RequestAssignment::isCurrentAssignee)
                .orElseThrow(() -> new IllegalStateException("수락된 요청인데 현재 담당자가 없습니다: " + request.getId()));

        RequestChangeProposal proposal = proposalRepository.save(RequestChangeProposal.create(request, assignee,
                changed, newStartsAt, newEndsAt, newPlace, newInformationIds, now));

        eventRepository.save(RequestChangeEvent.reconfirmationRequested(proposal, userId, now));
        // D: 처리 없음 (재확인 전에는 기존 권한 유지) / B: 담당자에게 재확인 요청 알림
        eventPublisher.publishEvent(CareRequestChangeProposedEvent.of(proposal, now));
        return UpdateCareRequestResponse.proposed(request.getId(), proposal.getId(), proposal.getResponseDeadline());
    }

    /**
     * 재확인 기한을 정할 시간이 있는지 (RequestChangeProposal의 기한 계산과 같은 기준)
     * 기한 = min(지금 + 24시간, 지금 시작, 바뀐 시작). 지금보다 뒤여야 함 → 아니면 409 REQUEST_PROPOSAL_NO_TIME
     * 시작 시각 전 수정만 받고 바뀐 시작은 1시간 이후라 보통은 걸리지 않지만, 엔티티 예외(500) 대신 이유가 담긴 409를 주려고 먼저 확인합니다.
     */
    private void requireProposalTime(CareRequest request, LocalDateTime newStartsAt, LocalDateTime now) {
        LocalDateTime deadline = now.plusHours(24);
        if (request.getStartsAt() != null && request.getStartsAt().isBefore(deadline)) {
            deadline = request.getStartsAt();
        }
        if (newStartsAt != null && newStartsAt.isBefore(deadline)) {
            deadline = newStartsAt;
        }
        if (!deadline.isAfter(now)) {
            throw new BusinessException(REQUEST_PROPOSAL_NO_TIME);
        }
    }

    private void invalidate(RequestChangeProposal proposal, InvalidationReason reason, Long userId, LocalDateTime now) {
        Objects.requireNonNull(proposal);
        proposal.invalidate();
        eventRepository.save(RequestChangeEvent.reconfirmationInvalidated(proposal, reason, userId, now));
    }
}
