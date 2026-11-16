package com.gyeot.domain.carerequest.query;

import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.enums.RequestType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * 공유(권한)용 요청 조회 인터페이스입니다. (C → D)
 * D의 정보 조회 검증과 공유 관리 카드 구성에 필요한 요청 정보를 제공합니다.
 * - 화면 표시용 요약은 CareRequestSummaryReader(B)를 사용합니다. 사용 목적이 달라 따로 두었습니다.
 * - 요청 상태는 화면용 RequestDisplayStatus가 아니라 DB의 CareRequestStatus를 그대로 제공합니다.
 *   D는 이 값으로 접근 가능 여부를 판단합니다.
 * - 관계 유지 및 상황 참여 여부(ERD 8.3)는 포함하지 않았습니다.
 */
public interface CareRequestSharingReader {

    /**
     * 요청 하나를 조회합니다. (정보 조회 시 검증)
     * 없으면 빈 Optional를 반환합니다.
     * */
    Optional<SharingRequestInfo> findById(Long requestId);

    /**
     * 요청 행을 잠그고 조회합니다. (D 공유 관리 다시 허용 이슈 #46)
     * 요청 완료·취소·수락 철회 등 C의 상태 변경과 D의 권한 변경이 겹치지 않게, D가 요청 행 → 항목 행 → 권한 행 순서로 잠글 때 씁니다.
     *
     * - 요청 행을 비관적 쓰기 잠금(SELECT ... FOR UPDATE)으로 조회합니다. 잠금은 호출한 트랜잭션이 끝날 때 풀립니다.
     * - C가 요청 상태·지정을 바꾸는 작업도 모두 같은 요청 행을 먼저 잠그므로, 잠금을 얻은 뒤의 값은 그 작업이 끝난 뒤의 최신 값입니다.
     * - 요청과 현재 담당자 값(assignmentId, assigneeId, acceptedAt)은 잠금 읽기로 가져와, 마지막으로 커밋된 값입니다.
     *   (같은 트랜잭션에서 먼저 다른 조회를 했어도 그 시점 스냅샷이 아니라 최신 값) 담당자 규칙은 findById와 같습니다.
     * - 요청 행은 처음부터 잠금 조회로 읽고 일반 조회를 먼저 하지 않습니다. (잠금 조회는 스냅샷을 만들지 않음)
     *   첫 일반 조회 전에 호출해야 이후 ID 조회의 스냅샷이 요청 잠금 획득 후 만들어집니다. 이미 생성된 스냅샷을 초기화하지는 않습니다.
     * - D가 잠금 뒤에 읽는 항목·권한 행도 잠금 읽기(FOR UPDATE 등)로 읽어야 최신 값입니다.
     *   MySQL 기본 격리 수준(REPEATABLE READ)에서 일반 조회는 트랜잭션의 첫 조회 시점 스냅샷을 보기 때문입니다.
     *   항목 삭제는 요청 잠금을 거치지 않으므로, 이 메서드를 먼저 불러도 항목·권한의 잠금 읽기는 그대로 필요합니다.
     * - 호출하는 쪽에 트랜잭션이 반드시 있어야 합니다. 없으면 IllegalTransactionStateException이 납니다.
     * - 요청이 없으면 빈 Optional을 반환합니다. (잠글 행이 없음)
     */
    Optional<SharingRequestInfo> findByIdForUpdate(Long requestId);

    /**
     * 여러 요청을 한 번에 조회합니다. (공유 관리 카드 목록)
     * 요청마다 따로 조회하면 카드 수만큼 쿼리가 나가서 묶어서 조회합니다.
     * 없는 ID는 결과에서 빠집니다.
     */
    Map<Long, SharingRequestInfo> findAllByIds(Collection<Long> requestIds);

    /**
     * 사용자가 이 요청의 대상으로 지정된 적이 있는지 확인합니다. (D 전달받은 정보 조회 이슈 #45)
     * 접근 종료 안내(200 + accessible=false)를 보여 줄 사람과 무관한 사람(404)을 구분하는 데 사용합니다.
     * - 지금 지정이든 마감된 과거 지정이든, 수락했든 안 했든 지정 행이 하나라도 있으면 true
     * - 요청자 본인, 지정된 적 없는 사람, 없는 요청은 false
     *
     * findById의 assigneeId는 현재 담당자만 담고 취소, 만료되면 null이라 과거 대상을 알 수 없습니다.
     * 또 공개 항목이 0개인 요청은 권한 행도 없어 D 데이터만으로는 확인할 수 없어서 따로 두었습니다.
     */
    boolean hasBeenAssigned(Long requestId, Long userId);

    /**
     * 공유 판단용 요청 정보
     * - situationId: 급한 요청은 null
     * - startsAt, endsAt: 급한 요청은 null (일반 요청 접근 기한은 endsAt, 급한 요청은 D의 expires_at)
     * - assignmentId, assigneeId, acceptedAt: 현재 담당자(수락했고 마감되지 않은 지정). 없으면 셋 다 null
     *   완료된 요청은 지정을 마감하지 않으므로 완료 후에도 마지막 담당자 값이 담깁니다.
     *   취소·만료된 요청은 지정을 마감하므로 null입니다. (ERD 9.1)
     */
    record SharingRequestInfo(
            Long requestId, // 요청 ID
            Long requesterId, // 요청자 ID (정보 주인과 같은지 확인, ERD 8.3 참고)
            Long assignmentId, // 현재 담당자의 대상 지정 ID (권한의 assignment_id와 비교, ERD 8.3 참고)
            CareRequestStatus status, // 요청 상태
            RequestType requestType, // 요청 유형
            LocalDateTime startsAt, // 시작 시간
            LocalDateTime endsAt, // 종료 시간
            Long situationId, // 상황 ID
            Long assigneeId, // 현재 담당자 ID
            LocalDateTime acceptedAt, // 수락 시간
            HelpType helpType // 도움 종류
    ) {
    }
}