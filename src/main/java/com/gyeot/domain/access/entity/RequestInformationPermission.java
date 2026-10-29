package com.gyeot.domain.access.entity;

import com.gyeot.domain.safetycard.entity.InformationType;
import com.gyeot.global.common.entity.BaseCreatedEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 요청 하나, 담당자 하나, 안심카드 항목 하나를 잇는 접근 권한입니다.
 * (ERD 4.11 request_information_permissions 요청별 정보 접근 권한)
 * 항목 1개 = 1행으로 저장합니다. (예: 복용약 2개, 알레르기 1개를 공개하면 3행)
 * 상태는 PENDING → ACTIVE → REVOKED 순서로만 바뀌고, 다시 허용할 때는 새 행을 만듭니다.
 * 수정 일시는 두지 않고, 언제 바뀌었는지는 granted_at, revoked_at에 남깁니다. 생성 일시는 BaseCreatedEntity가 채웁니다.
 */

@Getter                                             // 조회용 getter만 생성 (값 변경은 아래 메서드로만)
@NoArgsConstructor(access = AccessLevel.PROTECTED)  // JPA가 DB 값을 객체로 만들 때 쓰는 빈 생성자. 밖에서 new로 빈 객체를 못 만들게 막음
@Entity                                             // 이 클래스가 DB 테이블과 연결된다는 표시
@Table(
        name = "request_information_permissions",
        indexes = {
                // ERD 6.4 주요 인덱스 후보
                @Index(name = "idx_permissions_request_id_status", columnList = "request_id, status"),         // 요청 종료 시 권한 일괄 회수
                @Index(name = "idx_permissions_assignment_id_status", columnList = "assignment_id, status"),   // 담당자별 유효 권한, 담당자 변경 시 회수
                @Index(name = "idx_permissions_information_id_status", columnList = "information_id, status"), // 항목 삭제 시 회수
                @Index(name = "idx_permissions_status_expires_at", columnList = "status, expires_at"),         // 급한 요청 접근 기한 만료 회수
                @Index(name = "idx_permissions_status_revoked_at", columnList = "status, revoked_at")          // 보관 기간(2년) 지난 회수 권한 삭제
        }
)
public class RequestInformationPermission extends BaseCreatedEntity {

    @Id                                                  // 기본 키(PK)
    @GeneratedValue(strategy = GenerationType.IDENTITY)  // MySQL AUTO_INCREMENT로 번호 자동 발급
    private Long id;

    @Column(name = "request_id", nullable = false)  // 어떤 요청의 권한인지 (care_requests.id). 다른 도메인이라 ID 값만 저장
    private Long requestId;

    @Column(name = "assignment_id", nullable = false)  // 어떤 담당자 지정의 권한인지 (request_assignments.id). 다른 도메인이라 ID 값만 저장
    private Long assignmentId;

    @Column(name = "information_id")  // 공개한 안심카드 항목 (safety_information.id). 항목이 삭제되면 NULL
    private Long informationId;

    @Enumerated(EnumType.STRING)                                      // Enum 이름(예: MEDICATION)을 문자열로 저장
    @Column(name = "information_type", nullable = false, length = 40) // 공유 당시 항목 유형. 항목이 삭제돼도 무엇을 공유했는지 남김
    private InformationType informationType;

    @Enumerated(EnumType.STRING)                              // Enum 이름(예: ACTIVE)을 문자열로 저장
    @Column(name = "status", nullable = false, length = 20)   // 권한 상태 (PENDING, ACTIVE, REVOKED)
    private PermissionStatus status;

    @Column(name = "granted_at")  // ACTIVE가 된 시각. PENDING일 때는 NULL
    private LocalDateTime grantedAt;

    @Column(name = "expires_at")  // 급한 요청만: 수락 시각 + 24시간. 일반 요청은 NULL (요청 종료 시각으로 판단)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")  // REVOKED가 된 시각. 끝나기 전에는 NULL
    private LocalDateTime revokedAt;

    @Enumerated(EnumType.STRING)                        // Enum 이름(예: OWNER_REVOKED)을 문자열로 저장
    @Column(name = "revoke_reason", length = 40)        // 끝난 이유. 끝나기 전에는 NULL
    private RevokeReason revokeReason;


    // 요청을 보낼 때: 없음 → PENDING. 담당자가 수락하기 전이라 PENDING으로 만듦 (F-03)
    public static RequestInformationPermission createPending(Long requestId, Long assignmentId,
                                                             Long informationId, InformationType informationType) {
        RequestInformationPermission permission = new RequestInformationPermission();

        permission.requestId = requestId;
        permission.assignmentId = assignmentId;
        permission.informationId = informationId;
        permission.informationType = informationType;
        permission.status = PermissionStatus.PENDING;

        return permission;
    }


    // 이미 수락된 요청에 새로 공개할 때: 없음 → ACTIVE. 바로 ACTIVE로 만듦 (직접 철회 후 다시 허용, 요청 수정으로 항목 추가)
    public static RequestInformationPermission createActive(Long requestId, Long assignmentId,
                                                            Long informationId, InformationType informationType,
                                                            LocalDateTime grantedAt, LocalDateTime expiresAt) {
        RequestInformationPermission permission = createPending(requestId, assignmentId, informationId, informationType);
        permission.activate(grantedAt, expiresAt);

        return permission;
    }


    // 담당자가 수락했을 때: PENDING → ACTIVE (F-09). expiresAt은 급한 요청만 넣고, 일반 요청은 null
    public void activate(LocalDateTime grantedAt, LocalDateTime expiresAt) {
        if (this.status != PermissionStatus.PENDING) {
            throw new IllegalStateException("PENDING 상태의 권한만 활성화할 수 있습니다.");
        }

        this.status = PermissionStatus.ACTIVE;
        this.grantedAt = grantedAt;
        this.expiresAt = expiresAt;
    }


    // 권한을 끝낼 때: PENDING 또는 ACTIVE → REVOKED (F-09, F-30)
    // 이미 REVOKED면 아무것도 하지 않음 (같은 회수 이벤트가 두 번 와도 결과가 같도록)
    public void revoke(RevokeReason revokeReason, LocalDateTime revokedAt) {
        if (this.status == PermissionStatus.REVOKED) {
            return;
        }

        this.status = PermissionStatus.REVOKED;
        this.revokeReason = revokeReason;
        this.revokedAt = revokedAt;
    }


    // 지금 ACTIVE인지 (회수할 때 접근 이력을 남길지 판단, 조회 검증의 첫 조건)
    public boolean isActive() {
        return this.status == PermissionStatus.ACTIVE;
    }
}
