package com.gyeot.domain.carerequest.service;

import com.gyeot.domain.carerequest.dto.response.CareRequestDetailResponse;
import com.gyeot.domain.carerequest.dto.response.CareRequestDetailResponse.AssignmentView;
import com.gyeot.domain.carerequest.dto.response.CareRequestDetailResponse.CurrentAssignmentView;
import com.gyeot.domain.carerequest.dto.response.CareRequestDetailResponse.InformationView;
import com.gyeot.domain.carerequest.dto.response.CareRequestDetailResponse.MyAssignmentView;
import com.gyeot.domain.carerequest.dto.response.CareRequestDetailResponse.ProposalValues;
import com.gyeot.domain.carerequest.dto.response.CareRequestDetailResponse.ProposalView;
import com.gyeot.domain.carerequest.dto.response.CareRequestDetailResponse.SituationView;
import com.gyeot.domain.carerequest.dto.response.CareRequestDetailResponse.TargetView;
import com.gyeot.domain.carerequest.dto.response.CareRequestDetailResponse.UserView;
import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.entity.RequestChangeProposal;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.ChangeEventType;
import com.gyeot.domain.carerequest.enums.ChangeableField;
import com.gyeot.domain.carerequest.enums.InvalidationReason;
import com.gyeot.domain.carerequest.enums.NoAssigneeReason;
import com.gyeot.domain.carerequest.enums.ProposalStatus;
import com.gyeot.domain.carerequest.query.RequestDisplayStatus;
import com.gyeot.domain.carerequest.repository.RequestAssignmentRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeEventRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeProposalRepository;
import com.gyeot.domain.safetycard.query.SafetyInformationSharingReader;
import com.gyeot.domain.situation.query.SituationParticipantReader;
import com.gyeot.domain.situation.query.SituationParticipantReader.SituationSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 도움 요청 상세 조회를 처리합니다. (#69, F-08)
 * GET /api/care-requests/{requestId}
 *
 * - 관련 없는 사람(요청자·대상자(과거 대상 포함)·담당자가 아님)은 404 REQUEST_NOT_FOUND (CareRequestFinder)
 * - 역할마다 보여 줄 필드를 나눕니다. (CareRequestDetailResponse 참조)
 * - 이름은 A에서 한 번에, 보호자 여부는 요청자의 관계를 한 번에 가져옵니다. (지정 수만큼 조회하지 않음)
 * - 조회만 하므로 행을 잠그지 않습니다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CareRequestDetailService {

    private static final String SITUATION_ENDED = "ENDED"; // B SituationSummary.status (문자열 계약)

    private final CareRequestFinder careRequestFinder;
    private final RequestAssignmentRepository assignmentRepository;
    private final RequestChangeProposalRepository proposalRepository;
    private final RequestChangeEventRepository eventRepository;
    private final SituationParticipantReader situationReader;   // B: 상황 기간·상태
    private final SafetyInformationSharingReader sharingReader; // D: 지금 공개 중인 항목, 항목 이름
    private final RequestTargetEligibility targetEligibility;   // A: 보호자 여부
    private final RequestUserNames userNames;                   // A: 이름 (탈퇴자 "탈퇴한 사용자")
    private final Clock clock;

    public CareRequestDetailResponse getDetail(Long requestId, Long userId) {
        RequestAccess access = careRequestFinder.getAccessibleRequest(requestId, userId); // 관련 없으면 404
        LocalDateTime now = LocalDateTime.now(clock);
        CareRequest request = access.request();
        boolean isRequester = access.isRequester();

        // 지정 이력 (부탁 순서 1, 2, 3 ...). 마지막이 가장 최근 지정
        List<RequestAssignment> assignments = assignmentRepository.findAllByCareRequestIdOrderByAssignmentOrderAsc(requestId);
        RequestAssignment latest = assignments.isEmpty() ? null : assignments.get(assignments.size() - 1);
        RequestDisplayStatus displayStatus = RequestDisplayStatus.of(request, latest, now);

        SituationSummary situation = request.getSituationId() == null ? null
                : situationReader.findSituation(request.getSituationId()).orElse(null);
        RequestChangeProposal proposal = visibleProposal(access);

        // 이름: 요청자 + (요청자 뷰면) 지정된 모든 사람을 한 번에
        Set<Long> nameIds = new LinkedHashSet<>();
        nameIds.add(request.getRequesterId());
        if (isRequester) {
            assignments.forEach(assignment -> nameIds.add(assignment.getTargetUserId()));
        }
        Map<Long, String> names = userNames.namesOf(nameIds);
        Set<Long> guardianIds = isRequester
                ? targetEligibility.guardianIdsAmong(request.getRequesterId(), targetIdsOf(assignments))
                : Set.of();

        return new CareRequestDetailResponse(
                request.getId(),
                situation == null ? null : new SituationView(request.getSituationId(),
                        situation.startAt(), situation.endAt(), situation.status()),
                request.getRequestType(),
                request.getHelpType(),
                request.getDescription(),
                request.getPlace(),
                request.getStartsAt(),
                request.getEndsAt(),
                request.getStatus(),
                displayStatus,
                request.getCancelReason(),
                access.role(),
                new UserView(request.getRequesterId(), names.get(request.getRequesterId())),
                isRequester && latest != null ? currentAssignmentView(latest, names, guardianIds) : null,
                isRequester ? noAssigneeReason(displayStatus, latest, now) : null,
                isRequester ? assignmentViews(assignments, names, guardianIds) : null,
                isRequester && latest != null ? latest.getResponseDeadlineHours() : null,
                isRequester ? null : myAssignmentView(access.myAssignment()),
                proposal == null ? null : proposalView(request, proposal),
                actions(access, displayStatus, situation, proposal, now)
        );
    }

    // === 역할별 필드 ===

    private CurrentAssignmentView currentAssignmentView(RequestAssignment latest, Map<Long, String> names,
                                                        Set<Long> guardianIds) {
        return new CurrentAssignmentView(latest.getId(), targetView(latest, names, guardianIds),
                latest.getResponseStatus(), latest.getResponseDeadline(), latest.getAcceptedAt(),
                !latest.isOpen(), latest.getCloseReason());
    }

    private List<AssignmentView> assignmentViews(List<RequestAssignment> assignments, Map<Long, String> names,
                                                 Set<Long> guardianIds) {
        return assignments.stream()
                .map(assignment -> new AssignmentView(assignment.getAssignmentOrder(),
                        targetView(assignment, names, guardianIds),
                        assignment.getResponseStatus(), assignment.getCloseReason()))
                .toList();
    }

    private TargetView targetView(RequestAssignment assignment, Map<Long, String> names, Set<Long> guardianIds) {
        Long targetUserId = assignment.getTargetUserId();
        return new TargetView(targetUserId, names.get(targetUserId), guardianIds.contains(targetUserId));
    }

    private MyAssignmentView myAssignmentView(RequestAssignment mine) {
        return new MyAssignmentView(mine.getResponseStatus(), mine.getResponseDeadline(),
                !mine.isOpen(), mine.getCloseReason());
    }

    /** 담당자 없음일 때만 사유 (RequestDisplayStatus와 같은 기준인 RequestAssignment.currentNoAssigneeReason) */
    private NoAssigneeReason noAssigneeReason(RequestDisplayStatus displayStatus, RequestAssignment latest,
                                              LocalDateTime now) {
        if (displayStatus != RequestDisplayStatus.NO_ASSIGNEE || latest == null) {
            return null;
        }
        return latest.currentNoAssigneeReason(now);
    }

    private static Collection<Long> targetIdsOf(List<RequestAssignment> assignments) {
        return assignments.stream().map(RequestAssignment::getTargetUserId).toList();
    }

    // === 수정안 ===

    /**
     * 보는 사람에게 보여 줄 가장 최근 수정안
     * - 요청자: 요청의 가장 최근 수정안
     * - 담당자: 가장 최근 수정안이 내 지정에 온 것일 때만 (수락을 철회한 이전 담당자에게 다음 담당자의 수정안을 보여 주지 않음)
     * - 대상자(수락 전): 수정안은 수락 후에만 생기므로 null
     */
    private RequestChangeProposal visibleProposal(RequestAccess access) {
        if (access.role() == RequestViewerRole.TARGET) {
            return null;
        }
        RequestChangeProposal latest = proposalRepository
                .findFirstByCareRequestIdOrderByIdDesc(access.request().getId()).orElse(null);
        if (latest == null || access.isRequester()) {
            return latest;
        }
        return Objects.equals(latest.getAssignment().getId(), access.myAssignment().getId()) ? latest : null;
    }

    /**
     * before = 지금 요청 값 (공개 항목은 D의 지금 공개 중인 항목)
     * after = 수정안 값, 바뀌지 않은 필드는 before와 같은 값
     * 대기 중(PENDING)일 때 before가 "변경 전"입니다. 수락돼 반영된 뒤에는 before도 반영된 값이라 after와 같습니다.
     */
    private ProposalView proposalView(CareRequest request, RequestChangeProposal proposal) {
        Set<ChangeableField> changed = proposal.getChangedFields();
        List<Long> beforeIds = sharingReader.findSharingInformationIds(request.getId());
        List<Long> afterIds = proposal.changes(ChangeableField.INFORMATION_IDS)
                ? nullToEmpty(proposal.getProposedInformationIds()) : beforeIds;

        // 항목 이름은 변경 전후를 합쳐 한 번에 조회
        Set<Long> allIds = new LinkedHashSet<>(beforeIds);
        allIds.addAll(afterIds);
        Map<Long, String> informationNames = sharingReader.findInformationNames(request.getRequesterId(), allIds);

        ProposalValues before = new ProposalValues(request.getStartsAt(), request.getEndsAt(), request.getPlace(),
                informations(beforeIds, informationNames));
        ProposalValues after = new ProposalValues(
                changed.contains(ChangeableField.STARTS_AT) ? proposal.getProposedStartsAt() : request.getStartsAt(),
                changed.contains(ChangeableField.ENDS_AT) ? proposal.getProposedEndsAt() : request.getEndsAt(),
                changed.contains(ChangeableField.PLACE) ? proposal.getProposedPlace() : request.getPlace(),
                informations(afterIds, informationNames));

        return new ProposalView(proposal.getId(), proposal.getStatus(), invalidationReason(proposal),
                changed, before, after, proposal.getResponseDeadline());
    }

    /** C가 가진 ID 순서대로 이름을 꺼냅니다. D 결과에 없는 ID(삭제된 항목 등)는 뺍니다. */
    private static List<InformationView> informations(List<Long> ids, Map<Long, String> names) {
        List<InformationView> result = new ArrayList<>();
        for (Long id : ids) {
            String name = names.get(id);
            if (name != null) {
                result.add(new InformationView(id, name));
            }
        }
        return result;
    }

    /** 무효 사유는 RECONFIRMATION_INVALIDATED 이력의 event_details.reason에서 꺼냅니다. */
    private InvalidationReason invalidationReason(RequestChangeProposal proposal) {
        if (proposal.getStatus() != ProposalStatus.INVALIDATED) {
            return null;
        }
        return eventRepository
                .findFirstByProposalIdAndEventTypeOrderByIdDesc(proposal.getId(),
                        ChangeEventType.RECONFIRMATION_INVALIDATED)
                .map(event -> event.getEventDetails() == null ? null : event.getEventDetails().get("reason"))
                .map(reason -> InvalidationReason.valueOf(reason.toString()))
                .orElse(null);
    }

    private static List<Long> nullToEmpty(List<Long> ids) {
        return ids == null ? List.of() : ids;
    }

    // === 할 수 있는 동작 ===

    /** 각 API 서비스의 검증과 같은 기준으로 판단합니다. (RequestAction 참조) */
    private List<RequestAction> actions(RequestAccess access, RequestDisplayStatus displayStatus,
                                        SituationSummary situation, RequestChangeProposal proposal,
                                        LocalDateTime now) {
        CareRequest request = access.request();
        CareRequestStatus status = request.getStatus();
        boolean beforeStart = request.isUrgent() || now.isBefore(request.getStartsAt());
        boolean pendingProposal = proposal != null && proposal.isPending();
        List<RequestAction> actions = new ArrayList<>();

        if (access.isRequester()) {
            if (status == CareRequestStatus.IN_PROGRESS) {
                actions.add(RequestAction.COMPLETE);
            }
            if (!request.isTerminal()) {
                actions.add(RequestAction.CANCEL);
            }
            if ((status == CareRequestStatus.REQUESTED || status == CareRequestStatus.ACCEPTED) && beforeStart) {
                actions.add(RequestAction.EDIT);
            }
            if (displayStatus == RequestDisplayStatus.NO_ASSIGNEE && beforeStart) {
                actions.add(RequestAction.REASSIGN);
            }
            if (pendingProposal) {
                actions.add(RequestAction.WITHDRAW_PROPOSAL);
            }
            if (request.isTerminal()) {
                actions.add(RequestAction.DUPLICATE);
            }
            if (status == CareRequestStatus.COMPLETED && isEnded(situation)) {
                actions.add(RequestAction.VIEW_PAST_SITUATION);
            }
            return actions;
        }

        RequestAssignment mine = access.myAssignment();
        if (access.role() == RequestViewerRole.TARGET) {
            if (status == CareRequestStatus.REQUESTED && mine.isOpen() && beforeStart) {
                actions.add(RequestAction.RESPOND);
            }
            return actions;
        }

        // 담당자: 지금도 담당자일 때만 (수락을 철회했거나 마감된 이전 담당자는 동작 없음)
        if (!mine.isCurrentAssignee()) {
            return actions;
        }
        if (status == CareRequestStatus.ACCEPTED) {
            if (beforeStart) {
                actions.add(RequestAction.WITHDRAW);
            }
            actions.add(RequestAction.START); // 시작 버튼은 시작 시각 전에도 누를 수 있음
        }
        if (status == CareRequestStatus.IN_PROGRESS) {
            actions.add(RequestAction.COMPLETE);
        }
        if (pendingProposal && status == CareRequestStatus.ACCEPTED && beforeStart
                && !proposal.isDeadlinePassed(now)) {
            actions.add(RequestAction.ACCEPT_PROPOSAL);
            actions.add(RequestAction.REJECT_PROPOSAL);
        }
        if (status == CareRequestStatus.COMPLETED && isEnded(situation)) {
            actions.add(RequestAction.VIEW_PAST_SITUATION);
        }
        return actions;
    }

    private static boolean isEnded(SituationSummary situation) {
        return situation != null && SITUATION_ENDED.equals(situation.status());
    }
}
