package com.gyeot.domain.carerequest.query;
import com.gyeot.domain.carerequest.entity.CareRequest;
import com.gyeot.domain.carerequest.entity.RequestAssignment;
import com.gyeot.domain.carerequest.repository.CareRequestRepository;
import com.gyeot.domain.carerequest.repository.RequestAssignmentRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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
 * findByIdForUpdate는 요청 행을 잠그므로 Repository 대신 EntityManager로 직접 잠그고 다시 읽습니다.
 */

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CareRequestSharingReaderImpl implements CareRequestSharingReader {
    private final CareRequestRepository careRequestRepository;
    private final RequestAssignmentRepository assignmentRepository;
    private final EntityManager entityManager; // 요청 행 잠금 + 최신 값 다시 읽기 (findByIdForUpdate)

    @Override
    public Optional<SharingRequestInfo> findById(Long requestId) {
        Objects.requireNonNull(requestId, "requestId는 필수입니다.");
        // 한 건도 여러 건 조회와 같은 규칙으로 만들어 두 메서드의 결과가 어긋나지 않게 합니다.
        return Optional.ofNullable(findAllByIds(List.of(requestId)).get(requestId));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY) // 잠금은 호출한 쪽 트랜잭션 안에서만 의미가 있음
    public Optional<SharingRequestInfo> findByIdForUpdate(Long requestId) {
        Objects.requireNonNull(requestId, "requestId는 필수입니다.");

        // 1. 요청 행 잠금 (SELECT ... FOR UPDATE)
        // 처음부터 잠금 조회로 읽습니다. 일반 조회를 먼저 하면 MySQL(REPEATABLE READ)은 그 시점에 트랜잭션 스냅샷을 만들고,
        // 잠금을 기다리는 사이 다른 트랜잭션이 커밋한 변경이 이후 호출 측(D)의 일반 조회에 보이지 않습니다.
        // 잠금 조회는 스냅샷을 만들지 않으므로, 이후 첫 일반 조회의 스냅샷은 요청 잠금을 얻은 뒤에 만들어집니다.
        CareRequest request = entityManager.find(CareRequest.class, requestId, LockModeType.PESSIMISTIC_WRITE);
        if (request == null) {
            return Optional.empty(); // 잠글 행이 없음
        }
        // 같은 트랜잭션에서 이미 읽어 둔 엔티티라면 find는 잠그기만 하고 값은 예전 그대로일 수 있어,
        // refresh로 잠금과 함께 마지막으로 커밋된 값을 다시 읽습니다.
        entityManager.refresh(request, LockModeType.PESSIMISTIC_WRITE);

        // 2. 현재 담당자 (잠금 읽기로 최신 값)
        // MySQL 기본 격리 수준(REPEATABLE READ)에서 일반 SELECT는 트랜잭션의 첫 읽기 시점 스냅샷을 봅니다.
        // 그래서 요청 잠금을 기다리는 사이 다른 트랜잭션이 지정을 마감해도 일반 SELECT로는 예전 값이 보입니다.
        // 잠금 읽기(FOR SHARE)는 항상 마지막으로 커밋된 값을 읽으므로 지정도 잠금 읽기로 가져옵니다.
        // 지정을 바꾸는 C 작업은 모두 요청 행을 먼저 잠그므로, 요청 → 지정 순서로 잠가 순서가 어긋나지 않습니다.
        @SuppressWarnings("unchecked")
        List<Object[]> assignee = entityManager.createNativeQuery("""
                        select id, target_user_id, accepted_at
                        from request_assignments
                        where request_id = :requestId and closed_at is null and accepted_at is not null
                        for share
                        """)
                .setParameter("requestId", requestId)
                .unwrap(NativeQuery.class)
                .addScalar("id", Long.class)
                .addScalar("target_user_id", Long.class)
                .addScalar("accepted_at", LocalDateTime.class)
                .getResultList(); // 요청당 열린 지정은 최대 1건

        Object[] row = assignee.isEmpty() ? null : assignee.get(0);
        return Optional.of(new SharingRequestInfo(
                request.getId(),
                request.getRequesterId(),
                row == null ? null : (Long) row[0],
                request.getStatus(),
                request.getRequestType(),
                request.getStartsAt(),
                request.getEndsAt(),
                request.getSituationId(),
                row == null ? null : (Long) row[1],
                row == null ? null : (LocalDateTime) row[2],
                request.getHelpType()));
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

    @Override
    public boolean isCurrentTarget(Long requestId, Long userId) {
        Objects.requireNonNull(requestId, "requestId는 필수입니다.");
        Objects.requireNonNull(userId, "userId는 필수입니다.");
        return assignmentRepository.existsByCareRequestIdAndTargetUserIdAndClosedAtIsNull(requestId, userId);
    }

    @Override
    public Optional<Long> findLastAssignmentId(Long requestId) {
        Objects.requireNonNull(requestId, "requestId는 필수입니다.");

        // 화면 상태 계산과 같은 저장소 메서드를 재사용합니다. (assignment_order가 가장 큰 지정, 마감 여부 무관)
        // 지정 ID(자동 증가) 순서가 아니라 C가 정한 부탁 순서로 판단합니다.
        return assignmentRepository.findFirstByCareRequestIdOrderByAssignmentOrderDesc(requestId)
                .map(RequestAssignment::getId);
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