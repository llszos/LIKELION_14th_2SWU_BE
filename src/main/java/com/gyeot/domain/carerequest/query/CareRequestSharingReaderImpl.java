package com.gyeot.domain.carerequest.query;
import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.repository.CareRequestRepository;
import com.gyeot.domain.carerequest.repository.RequestAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * CareRequestSharingReader(공유용 요청 조회 인터페이스)의 실제 구현 내용입니다. (C → D, 이슈 #68)
 * 사용하는 쪽(D)은 이 구현 클래스가 아니라 CareRequestSharingReader 타입으로 선언합니다.
 *
 * 요청이 몇 개든 쿼리는 2번입니다. (요청 IN 조회 1번 + 현재 담당자 지정 IN 조회 1번)
 */

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CareRequestSharingReaderImpl implements CareRequestSharingReader {
    private final CareRequestRepository careRequestRepository;
    private final RequestAssignmentRepository assignmentRepository;

    @Override
    public Optional<SharingRequestInfo> findById(Long requestId) {
        Objects.requireNonNull(requestId, "requestId는 필수입니다.");
        // 한 건도 여러 건 조회와 같은 규칙으로 만들어 두 메서드의 결과가 어긋나지 않게 합니다.
        return Optional.ofNullable(findAllByIds(List.of(requestId)).get(requestId));
    }

    @Override
    public Map<Long, SharingRequestInfo> findAllByIds(Collection<Long> requestIds) {
        if (requestIds == null || requestIds.isEmpty()) {
            return Map.of(); // 빈 IN ( ) 쿼리를 보내지 않음
        }

        // 1. 요청을 한 번에 조회합니다. (없는 ID는 결과에 없음)
        List<CareRequest> requests = careRequestRepository.findAllById(requestIds);
        if (requests.isEmpty()) {
            return Map.of();
        }

        // 2. 현재 담당자 지정을 한 번에 조회합니다. → 요청 ID별로 정리
        List<Long> foundIds = requests.stream().map(CareRequest::getId).toList();
        Map<Long, RequestAssignment> assigneeByRequestId = assignmentRepository
                .findAllByCareRequestIdInAndClosedAtIsNullAndAcceptedAtIsNotNull(foundIds)
                .stream()
                .collect(Collectors.toMap(a -> a.getCareRequest().getId(), Function.identity()));

        // 3. 요청 + (있으면) 현재 담당자를 합쳐 결과를 만듭니다.
        return requests.stream()
                .collect(Collectors.toMap(CareRequest::getId,
                        request -> toInfo(request, assigneeByRequestId.get(request.getId()))));
    }

    @Override
    public boolean hasBeenAssigned(Long requestId, Long userId) {
        Objects.requireNonNull(requestId, "requestId는 필수입니다.");
        Objects.requireNonNull(userId, "userId는 필수입니다.");
        return assignmentRepository.existsByCareRequestIdAndTargetUserId(requestId, userId);
    }

    /**
     * 현재 담당자가 없으면(currentAssignee == null) 담당자 관련 세 값은 null
     */
    private SharingRequestInfo toInfo(CareRequest request, RequestAssignment currentAssignee) {
        return new SharingRequestInfo(
                request.getId(),
                request.getRequesterId(),
                currentAssignee == null ? null : currentAssignee.getId(),
                request.getStatus(),
                request.getRequestType(),
                request.getStartsAt(),
                request.getEndsAt(),
                request.getSituationId(),
                currentAssignee == null ? null : currentAssignee.getTargetUserId(),
                currentAssignee == null ? null : currentAssignee.getAcceptedAt(),
                request.getHelpType());
    }
}
