package com.gyeot.domain.carerequest.service;

import com.gyeot.domain.carerequest.dto.response.TargetCandidatesResponse;
import com.gyeot.domain.carerequest.dto.response.TargetCandidatesResponse.CandidateView;
import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.repository.RequestAssignmentRepository;
import com.gyeot.domain.carerequest.service.RequestTargetEligibility.Candidate;
import com.gyeot.domain.situation.query.SituationParticipantReader;
import com.gyeot.domain.situation.query.SituationParticipantReader.SituationSummary;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.gyeot.domain.carerequest.exception.CareRequestErrorCode.*;

/**
 * 대상 후보 조회를 처리합니다. (#108, F-05·F-26)
 * GET /api/care-requests/target-candidates?situationId=&requestId=
 *
 * - H-03-02 요청 작성: situationId만 보냄 → 그 상황의 후보
 * - N-06 다시 부탁하기: requestId를 보냄(situationId 생략 가능) → 그 요청의 상황 기준 후보에서 이 요청에 부탁했던 사람(현재 대상 포함) 제외
 *
 * 처리 순서
 * 1. 둘 다 없으면 400 INVALID_INPUT
 * 2. requestId가 있으면: 관련 없으면 404, 요청자가 아니면 403, situationId도 보냈는데 요청의 상황과 다르면 404
 * 3. 상황 확인 (요청 생성과 같은 기준): 없거나 내 상황이 아니면 400 REQUEST_SITUATION_INVALID, 종료면 409 REQUEST_SITUATION_ENDED
 * 4. 후보 계산은 RequestTargetEligibility.findCandidates (요청 생성·다시 부탁하기의 REQUEST_TARGET_INVALID 검증과 같은 기준)
 * 5. 이름은 A에서 한 번에 조회 (탈퇴자 "탈퇴한 사용자")
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TargetCandidateService {

    private static final String SITUATION_ENDED = "ENDED"; // B SituationSummary.status (문자열 계약)

    private final CareRequestFinder careRequestFinder;
    private final RequestAssignmentRepository assignmentRepository;
    private final SituationParticipantReader situationReader; // B: 상황 생성자·상태
    private final RequestTargetEligibility targetEligibility; // B 참여자 + A 관계
    private final RequestUserNames userNames;                 // A: 이름

    public TargetCandidatesResponse findCandidates(Long userId, Long situationId, Long requestId) {
        // 1. 입력 확인
        if (situationId == null && requestId == null) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }

        // 2. 다시 부탁하기(N-06): 요청 확인 + 이전 대상 제외 목록
        Set<Long> excluded = Set.of();
        Long targetSituationId = situationId;
        if (requestId != null) {
            CareRequest request = requestOfRequester(requestId, userId);
            if (situationId != null && !Objects.equals(situationId, request.getSituationId())) {
                throw new BusinessException(REQUEST_NOT_FOUND); // 요청의 상황과 다른 상황을 보냄
            }
            targetSituationId = request.getSituationId();
            excluded = assignmentRepository.findAllByCareRequestIdOrderByAssignmentOrderAsc(requestId).stream()
                    .map(RequestAssignment::getTargetUserId)
                    .collect(Collectors.toSet());
        }

        // 3. 상황 확인
        requireMyActiveSituation(targetSituationId, userId);

        // 4. 후보 계산 → 5. 이름
        Set<Long> excludedTargets = excluded;
        List<Candidate> candidates = targetEligibility.findCandidates(targetSituationId, userId).stream()
                .filter(candidate -> !excludedTargets.contains(candidate.userId()))
                .toList();
        Map<Long, String> names = userNames.namesOf(candidates.stream().map(Candidate::userId).toList());

        return new TargetCandidatesResponse(candidates.stream()
                .map(candidate -> new CandidateView(candidate.userId(), names.get(candidate.userId()),
                        candidate.isGuardian()))
                .toList());
    }

    /** 관련 없으면 404, 요청자가 아니면 403 (다시 부탁하기와 같은 기준) */
    private CareRequest requestOfRequester(Long requestId, Long userId) {
        RequestAccess access = careRequestFinder.getAccessibleRequest(requestId, userId);
        if (!access.isRequester()) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        if (access.request().getSituationId() == null) {
            throw new BusinessException(REQUEST_SITUATION_INVALID); // 상황 밖 요청(급한 요청)은 상황 후보가 없음
        }
        return access.request();
    }

    /** 요청 생성과 같은 기준: 다른 사람 상황은 종료 여부를 알리지 않고 400 */
    private void requireMyActiveSituation(Long situationId, Long userId) {
        SituationSummary situation = situationReader.findSituation(situationId)
                .filter(found -> found.creatorId().equals(userId))
                .orElseThrow(() -> new BusinessException(REQUEST_SITUATION_INVALID));
        if (SITUATION_ENDED.equals(situation.status())) {
            throw new BusinessException(REQUEST_SITUATION_ENDED);
        }
    }
}
