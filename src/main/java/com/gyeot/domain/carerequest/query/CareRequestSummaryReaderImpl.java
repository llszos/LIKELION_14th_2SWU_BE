package com.gyeot.domain.carerequest.query;

import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.entity.RequestChangeProposal;
import com.gyeot.domain.carerequest.enums.AssignmentCloseReason;
import com.gyeot.domain.carerequest.enums.AssignmentResponseStatus;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.ProposalStatus;
import com.gyeot.domain.carerequest.repository.CareRequestRepository;
import com.gyeot.domain.carerequest.repository.CareRequestRepository.SituationRequestCount;
import com.gyeot.domain.carerequest.repository.RequestAssignmentRepository;
import com.gyeot.domain.carerequest.repository.RequestChangeProposalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.gyeot.domain.carerequest.enums.CareRequestStatus.*;

/**
 * CareRequestSummaryReader(B 화면용 요청 요약 조회)의 실제 구현입니다. (#67)
 * 사용하는 쪽(B)은 이 구현 클래스가 아니라 CareRequestSummaryReader 타입으로 선언합니다.
 *
 * 모든 메서드가 같은 패턴입니다.
 * - 1. 필요한 요청을 한 번에 조회
 * - 2. 그 요청들의 지정을 한 번에 조회해 요청 ID별로 묶음
 * - 3. 자바에서 계산
 * 요청이나 상황마다 따로 조회하지 않아서, 개수가 늘어도 쿼리 수가 늘지 않습니다. (N+1 방지)
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CareRequestSummaryReaderImpl implements CareRequestSummaryReader {

    private static final List<CareRequestStatus> ONGOING = List.of(REQUESTED, ACCEPTED, IN_PROGRESS); // 끝나지 않은 요청
    private static final List<CareRequestStatus> SCHEDULED = List.of(ACCEPTED, IN_PROGRESS);         // 일정이 잡힌 요청
    private static final List<CareRequestStatus> ENDED = List.of(COMPLETED, CANCELED, EXPIRED);       // 끝난 요청
    private static final int HOME_UPCOMING_LIMIT = 2; // 홈 다가오는 일정 최대 개수

    /** 시작이 빠른 순, 같으면 요청 ID 순 (급한 요청은 시작 시각이 없어 맨 뒤) */
    private static final Comparator<CareRequest> BY_START = Comparator
            .comparing(CareRequest::getStartsAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(CareRequest::getId);

    private final CareRequestRepository careRequestRepository;
    private final RequestAssignmentRepository assignmentRepository;
    private final RequestChangeProposalRepository proposalRepository;

    // === S-00 상황 탭, H-00 홈: 진행 중 상황 카드 ===

    @Override
    public Map<Long, SituationRequestStats> getSituationStats(Collection<Long> situationIds, LocalDateTime now) {
        if (situationIds == null || situationIds.isEmpty()) {
            return Map.of();
        }
        List<CareRequest> requests = careRequestRepository.findAllBySituationIdInAndStatusIn(situationIds, ONGOING);
        Map<Long, List<RequestAssignment>> assignments = assignmentsOf(requests);
        Map<Long, List<CareRequest>> bySituation = requests.stream()
                .collect(Collectors.groupingBy(CareRequest::getSituationId));

        // 요청이 없는 상황도 0건 통계로 채움 (받은 상황 ID 순서 그대로)
        Map<Long, SituationRequestStats> result = new LinkedHashMap<>();
        for (Long situationId : situationIds) {
            List<CareRequest> inSituation = bySituation.getOrDefault(situationId, List.of());
            result.put(situationId, situationStats(inSituation, assignments, now));
        }
        return result;
    }

    private SituationRequestStats situationStats(List<CareRequest> requests,
                                                 Map<Long, List<RequestAssignment>> assignments, LocalDateTime now) {
        if (requests.isEmpty()) {
            return SituationRequestStats.empty();
        }
        Map<RequestDisplayStatus, Long> counts = requests.stream()
                .collect(Collectors.groupingBy(r -> displayStatus(r, assignments, now), Collectors.counting()));

        UpcomingSchedule next = requests.stream()
                .filter(r -> SCHEDULED.contains(r.getStatus()))
                .min(BY_START)
                .map(r -> upcoming(r, currentAssignee(assignments.get(r.getId()))))
                .orElse(null);

        return new SituationRequestStats(
                count(counts, RequestDisplayStatus.ACCEPTED),
                count(counts, RequestDisplayStatus.IN_PROGRESS),
                count(counts, RequestDisplayStatus.WAITING),
                count(counts, RequestDisplayStatus.NO_ASSIGNEE),
                next);
    }

    // === H-03 상황 상세 ===

    @Override
    public List<RequestSummary> getSituationRequests(Long situationId, Long viewerId, LocalDateTime now) {
        List<CareRequest> requests = careRequestRepository.findAllBySituationId(situationId);
        Map<Long, List<RequestAssignment>> assignments = assignmentsOf(requests);

        return requests.stream()
                .filter(r -> isVisibleInSituation(r, assignments.get(r.getId()), viewerId))
                .sorted(BY_START)
                .map(r -> summary(r, assignments, now))
                .toList();
    }

    /**
     * 상황 상세 목록에 보이는지
     * - 요청자: 전부
     * - 그 외: 지금 내가 대상자·담당자(열린 지정)이거나, 끝난 요청의 기록상 담당자
     */
    private boolean isVisibleInSituation(CareRequest request, List<RequestAssignment> assignments, Long viewerId) {
        if (request.getRequesterId().equals(viewerId)) {
            return true;
        }
        List<RequestAssignment> list = orEmpty(assignments);
        boolean currentlyMine = list.stream()
                .anyMatch(a -> a.isOpen() && a.getTargetUserId().equals(viewerId));
        return currentlyMine || isRecordAssignee(request, list, viewerId);
    }

    // === H-00 홈 ===

    @Override
    public HomeRequestOverview getHomeOverview(Long userId, LocalDateTime now) {
        // 내가 지금 대상자·담당자인 지정 (요청과 함께)
        List<RequestAssignment> myOpenAssignments = assignmentRepository.findOpenAssignmentsWithRequest(userId);

        // 1. 응답이 필요한 요청 (새 요청 응답 + 재확인)
        List<ResponseNeededItem> responseNeeded = new ArrayList<>();
        for (RequestAssignment assignment : myOpenAssignments) {
            if (needsResponse(assignment, now)) {
                CareRequest r = assignment.getCareRequest();
                responseNeeded.add(new ResponseNeededItem(r.getId(), ResponseNeededType.RESPONSE, r.getRequestType(),
                        r.getHelpType(), r.getStartsAt(), r.getPlace(), r.getRequesterId(),
                        assignment.getResponseDeadline()));
            }
        }
        for (RequestChangeProposal proposal : proposalRepository.findAllForAssignee(userId, ProposalStatus.PENDING)) {
            if (needsReconfirmation(proposal, now)) {
                CareRequest r = proposal.getCareRequest();
                responseNeeded.add(new ResponseNeededItem(r.getId(), ResponseNeededType.RECONFIRMATION,
                        r.getRequestType(), r.getHelpType(), r.getStartsAt(), r.getPlace(), r.getRequesterId(),
                        proposal.getResponseDeadline()));
            }
        }

        responseNeeded.sort(Comparator.comparing(ResponseNeededItem::deadline)
                .thenComparing(ResponseNeededItem::requestId));

        // 2. 내가 보낸 끝나지 않은 요청
        List<CareRequest> sent = careRequestRepository.findAllByRequesterIdAndStatusIn(userId, ONGOING);
        Map<Long, List<RequestAssignment>> sentAssignments = assignmentsOf(sent);
        List<RequestSummary> sentRequests = sent.stream()
                .sorted(BY_START)
                .map(r -> summary(r, sentAssignments, now))
                .toList();
        int noAssigneeCount = (int) sentRequests.stream()
                .filter(s -> s.displayStatus() == RequestDisplayStatus.NO_ASSIGNEE)
                .count();

        // 3. 다가오는 일정: 내가 부탁한 + 내가 맡은 수락됨·진행 중 요청
        Map<Long, UpcomingSchedule> upcomingById = new LinkedHashMap<>();
        sent.stream()
                .filter(r -> SCHEDULED.contains(r.getStatus()))
                .forEach(r -> upcomingById.put(r.getId(), upcoming(r, currentAssignee(sentAssignments.get(r.getId())))));
        myOpenAssignments.stream()
                .filter(RequestAssignment::isCurrentAssignee)
                .filter(a -> SCHEDULED.contains(a.getCareRequest().getStatus()))
                .forEach(a -> upcomingById.put(a.getCareRequest().getId(), upcoming(a.getCareRequest(), a)));
        List<UpcomingSchedule> upcoming = upcomingById.values().stream()
                .sorted(Comparator.comparing(UpcomingSchedule::startsAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(UpcomingSchedule::requestId))
                .toList();

        return new HomeRequestOverview(
                responseNeeded.size(),
                noAssigneeCount,
                upcoming.size(),
                responseNeeded,
                sentRequests,
                upcoming.stream().limit(HOME_UPCOMING_LIMIT).toList());
    }

    /**
     * 새 요청 응답이 필요한지 (F-06)
     * 요청됨 + 아직 수락 안 함 + 미응답 또는 확인 후 답변 + 아직 응답할 수 있음
     * - 일반 요청: 응답 기한이 지나도 시작 전이면 응답할 수 있으므로 시작 전까지 포함
     * - 급한 요청: 응답 기한(30분)이 지나면 응답할 수 없으므로 기한 전까지
     */
    private boolean needsResponse(RequestAssignment assignment, LocalDateTime now) {
        CareRequest request = assignment.getCareRequest();
        AssignmentResponseStatus response = assignment.getResponseStatus();
        boolean unanswered = response == null || response == AssignmentResponseStatus.CHECK_LATER;
        boolean respondable = request.isUrgent()
                ? !assignment.isDeadlinePassed(now)
                : now.isBefore(request.getStartsAt());
        return request.getStatus() == REQUESTED && !assignment.isAccepted() && unanswered && respondable;
    }

    /** 재확인이 필요한지: 대기 중 + 내가 지금 담당자 + 요청이 수락됨 + 재확인 기한 전 (F-29) */
    private boolean needsReconfirmation(RequestChangeProposal proposal, LocalDateTime now) {
        return proposal.isPending()
                && proposal.getAssignment().isCurrentAssignee()
                && proposal.getCareRequest().getStatus() == ACCEPTED
                && !proposal.isDeadlinePassed(now);
    }

    // === H-04 지난 상황, N-04 지난 상황 상세 ===

    @Override
    public Map<Long, PastSituationStats> getPastSituationStats(Collection<Long> situationIds) {
        if (situationIds == null || situationIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, SituationRequestCount> counts = careRequestRepository.countBySituations(situationIds, COMPLETED)
                .stream()
                .collect(Collectors.toMap(SituationRequestCount::getSituationId, Function.identity()));

        Map<Long, PastSituationStats> result = new LinkedHashMap<>();
        for (Long situationId : situationIds) {
            SituationRequestCount count = counts.get(situationId);
            result.put(situationId, count == null
                    ? PastSituationStats.empty()
                    : new PastSituationStats((int) count.getRequestCount(), (int) count.getCompletedCount()));
        }
        return result;
    }

    @Override
    public List<PastRequestRecord> getPastRequestRecords(Long situationId, Long viewerId) {
        List<CareRequest> ended = careRequestRepository.findAllBySituationId(situationId).stream()
                .filter(CareRequest::isTerminal)
                .toList();
        Map<Long, List<RequestAssignment>> assignments = assignmentsOf(ended);

        List<PastRequestRecord> records = new ArrayList<>();
        for (CareRequest request : ended.stream().sorted(BY_START).toList()) {
            Long assigneeId = recordAssigneeId(request, assignments.get(request.getId()));
            boolean visible = request.getRequesterId().equals(viewerId) || Objects.equals(assigneeId, viewerId);
            if (visible) {
                records.add(new PastRequestRecord(request.getId(), request.getHelpType(), request.getStartsAt(),
                        request.getRequesterId(), assigneeId, RequestDisplayStatus.of(request, null, null)));
            }
        }
        return records;
    }

    // === 도움 기록 (F-22, B #64) ===

    @Override
    public Page<HelpRecord> getHelpRecords(Long userId, HelpRecordFilter filter, Long personId, Pageable pageable) {
        Objects.requireNonNull(userId, "userId는 필수입니다.");
        Objects.requireNonNull(filter, "filter는 필수입니다.");

        // 정렬은 C가 쿼리에서 정하므로, 받은 pageable에서 페이지 번호, 크기만 씀
        Pageable pageOnly = pageable.isPaged()
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize())
                : Pageable.unpaged();

        Page<CareRequest> page = careRequestRepository.findHelpRecords(userId,
                filter != HelpRecordFilter.GIVEN, filter != HelpRecordFilter.RECEIVED, personId,
                ENDED, AssignmentCloseReason.REQUEST_ENDED, pageOnly);

        // 이번 페이지 요청들의 지정만 한 번에 조회해 기록상 담당자를 계산
        Map<Long, List<RequestAssignment>> assignments = assignmentsOf(page.getContent());
        return page.map(request -> new HelpRecord(
                request.getId(),
                request.getSituationId(),
                request.getHelpType(),
                request.getStartsAt(),
                request.getRequesterId(),
                recordAssigneeId(request, assignments.get(request.getId())),
                RequestDisplayStatus.of(request, null, null),
                request.getRequesterId().equals(userId) ? HelpRecordFilter.RECEIVED : HelpRecordFilter.GIVEN));
    }

    // === 공통 도우미 ===

    /** 요청들의 지정을 한 번에 조회해 요청 ID별로 묶음 */
    private Map<Long, List<RequestAssignment>> assignmentsOf(Collection<CareRequest> requests) {
        if (requests.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = requests.stream().map(CareRequest::getId).toList();
        return assignmentRepository.findAllByCareRequestIdIn(ids).stream()
                .collect(Collectors.groupingBy(a -> a.getCareRequest().getId()));
    }

    /** 화면 상태 (가장 최근 지정 기준, RequestDisplayStatus 한 곳에서 계산) */
    private RequestDisplayStatus displayStatus(CareRequest request, Map<Long, List<RequestAssignment>> assignments,
                                               LocalDateTime now) {
        return RequestDisplayStatus.of(request, latest(assignments.get(request.getId())), now);
    }

    private RequestSummary summary(CareRequest request, Map<Long, List<RequestAssignment>> assignments,
                                   LocalDateTime now) {
        RequestAssignment latest = latest(assignments.get(request.getId()));
        return new RequestSummary(request.getId(), request.getRequestType(), request.getHelpType(),
                request.getStartsAt(), request.getEndsAt(), request.getRequesterId(),
                latest == null ? null : latest.getTargetUserId(),
                RequestDisplayStatus.of(request, latest, now));
    }

    private UpcomingSchedule upcoming(CareRequest request, RequestAssignment assignee) {
        return new UpcomingSchedule(request.getId(), request.getHelpType(), request.getStartsAt(), request.getPlace(),
                request.getRequesterId(), assignee == null ? null : assignee.getTargetUserId());
    }

    /** 가장 최근 지정 (지정 순서가 가장 큰 것). 없으면 null */
    private RequestAssignment latest(List<RequestAssignment> assignments) {
        return orEmpty(assignments).stream()
                .max(Comparator.comparingInt(RequestAssignment::getAssignmentOrder))
                .orElse(null);
    }

    /** 현재 담당자 지정 (수락했고 마감되지 않음). 없으면 null */
    private RequestAssignment currentAssignee(List<RequestAssignment> assignments) {
        return orEmpty(assignments).stream()
                .filter(RequestAssignment::isCurrentAssignee)
                .findFirst()
                .orElse(null);
    }

    private Long recordAssigneeId(CareRequest request, List<RequestAssignment> assignments) {
        return RecordAssigneeRule.find(request, orEmpty(assignments))
                .map(RequestAssignment::getTargetUserId)
                .orElse(null);
    }

    private boolean isRecordAssignee(CareRequest request, List<RequestAssignment> assignments, Long userId) {
        return request.isTerminal() && Objects.equals(recordAssigneeId(request, assignments), userId);
    }

    private static List<RequestAssignment> orEmpty(List<RequestAssignment> assignments) {
        return assignments == null ? List.of() : assignments;
    }

    private static int count(Map<RequestDisplayStatus, Long> counts, RequestDisplayStatus status) {
        return counts.getOrDefault(status, 0L).intValue();
    }
}