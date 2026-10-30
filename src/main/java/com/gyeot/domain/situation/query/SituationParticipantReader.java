package com.gyeot.domain.situation.query;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * C의 일반 돌봄 요청 검증에 필요한 상황·참여 정보를 제공하는 B의 조회 계약입니다.
 * Entity를 반환하지 않으며, 관계 자격 검증은 A, 요청 생성·일정 검증은 C가 담당합니다.
 * 구현체와 DB 조회는 후속 작업입니다. 급한 요청에는 상황 참여 검증을 적용하지 않습니다.
 */
public interface SituationParticipantReader {

    /**
     * 해당 상황·사용자의 ACTIVE 참여 행이 존재하는지 확인합니다.
     * 참여 행이 없거나 LEFT·REMOVED이면 false입니다. 상황이 없을 때도 false입니다.
     * 상황 자체의 ACTIVE·ENDED 상태는 이 결과에 합치지 않고 findSituation으로 별도 확인합니다.
     *
     * @param situationId 상황 ID, null 불가
     * @param userId 참여 여부를 확인할 사용자 ID, null 불가
     * @return ACTIVE 참여 행이 존재하면 true
     * @throws NullPointerException 인자 중 하나라도 null인 경우 (구현체에서 검증)
     */
    boolean isActiveParticipant(Long situationId, Long userId);

    /**
     * 생성자·기간·상태 검증에 필요한 상황 정보를 조회합니다. 종료된 상황도 반환합니다.
     * 반환된 날짜의 시간 경계와 요청 자격은 C가 합의된 업무 규칙에 따라 검증합니다.
     *
     * @param situationId 상황 ID, null 불가
     * @return 상황이 있으면 요약 정보, 없으면 Optional.empty(). Optional 자체는 null 불가
     * @throws NullPointerException situationId가 null인 경우 (구현체에서 검증)
     */
    Optional<SituationSummary> findSituation(Long situationId);

    /**
     * 도메인 간 조회 결과입니다. 모든 필드는 필수입니다.
     * status는 SituationStatus.name()에 해당하는 ACTIVE 또는 ENDED 문자열입니다.
     * 준비 브랜치의 enum 파일을 중복 추가하지 않도록 Entity·enum 타입에 의존하지 않습니다.
     * TODO: 상황 Entity와 SituationStatus가 develop에 병합되면 status 타입을 SituationStatus로 전환하고,
     * 조회 구현체·호출부·테스트의 문자열 사용도 함께 변경합니다.
     *
     * @param creatorId 상황 생성자 ID (users.id)
     * @param startDate 상황 시작 날짜 (한국 날짜 기준)
     * @param endDate 상황 종료 날짜 (한국 날짜 기준)
     * @param status 상황 상태 코드. 참여 상태나 날짜로 계산한 화면 상태가 아닙니다.
     */
    record SituationSummary(Long creatorId, LocalDate startDate, LocalDate endDate, String status) {
        public SituationSummary {
            Objects.requireNonNull(creatorId, "creatorId는 필수입니다.");
            Objects.requireNonNull(startDate, "startDate는 필수입니다.");
            Objects.requireNonNull(endDate, "endDate는 필수입니다.");
            Objects.requireNonNull(status, "status는 필수입니다.");
        }
    }
}
