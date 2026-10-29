package com.gyeot.domain.access.entity;

import com.gyeot.domain.safetycard.entity.InformationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 공유한 안심카드 항목에 일어난 일(부여, 조회, 회수, 철회)을 한 건씩 남기는 기록입니다.
 * (ERD 4.12 information_access_logs 정보 접근 이력)
 * 한 번 저장하면 누구도 수정할 수 없고, 2년 보관 후 스케줄러가 삭제합니다.
 * 정보 내용이나 사용자 이름 같은 개인정보는 저장하지 않고, ID, 유형, 시각만 저장합니다.
 * 생성, 수정 일시 대신 사건이 일어난 시각(occurred_at)만 두므로 공통 엔티티를 상속하지 않습니다.
 */

@Getter                                             // 조회용 getter만 생성 (값을 바꾸는 메서드는 없음)
@NoArgsConstructor(access = AccessLevel.PROTECTED)  // JPA가 DB 값을 객체로 만들 때 쓰는 빈 생성자. 밖에서 new로 빈 객체를 못 만들게 막음
@Entity                                             // 이 클래스가 DB 테이블과 연결된다는 표시
@Table(
        name = "information_access_logs",
        indexes = {
                // ERD 6.4 주요 인덱스 후보
                @Index(name = "idx_access_logs_owner_user_id_actor_user_id_occurred_at", columnList = "owner_user_id, actor_user_id, occurred_at"),           // 사람별 접근 이력 (F-25 사람 필터)
                @Index(name = "idx_access_logs_owner_user_id_information_type_occurred_at", columnList = "owner_user_id, information_type, occurred_at"),     // 항목 유형별 접근 이력 (F-25 항목 필터)
                @Index(name = "idx_access_logs_owner_user_id_information_id_occurred_at", columnList = "owner_user_id, information_id, occurred_at"),         // 개별 항목별 접근 이력 (F-25 'n번 조회' 상세)
                @Index(name = "idx_access_logs_occurred_at", columnList = "occurred_at")                                                                       // 보관 기간(2년) 지난 이력 삭제
        }
)
public class InformationAccessLog {

    @Id                                                  // 기본 키(PK)
    @GeneratedValue(strategy = GenerationType.IDENTITY)  // MySQL AUTO_INCREMENT로 번호 자동 발급
    private Long id;

    @Column(name = "permission_id", updatable = false)  // 관련 접근 권한 (request_information_permissions.id). 권한 행이 삭제되면 NULL
    private Long permissionId;

    @Column(name = "owner_user_id", nullable = false, updatable = false)  // 정보 주인 ID (users.id). 이력을 조회하는 사람
    private Long ownerUserId;

    @Column(name = "actor_user_id", updatable = false)  // 이 일을 한 사람 ID (users.id). 시스템 자동 처리(요청 종료, 기한 만료 등)는 NULL
    private Long actorUserId;

    @Column(name = "information_id", nullable = false, updatable = false)  // 접근한 안심카드 항목 ID 스냅샷. 항목이 삭제돼도 값은 그대로 남김 (FK 없음)
    private Long informationId;

    @Enumerated(EnumType.STRING)                                                          // Enum 이름(예: MEDICATION)을 문자열로 저장
    @Column(name = "information_type", nullable = false, updatable = false, length = 40)  // 접근한 항목 유형. 항목이 삭제되면 화면에 이 이름만 보여줌
    private InformationType informationType;

    @Enumerated(EnumType.STRING)                                                    // Enum 이름(예: VIEWED)을 문자열로 저장
    @Column(name = "event_type", nullable = false, updatable = false, length = 40)  // 무슨 일이 있었는지 (GRANTED, VIEWED, AUTO_REVOKED, OWNER_REVOKED)
    private AccessEventType eventType;

    @Column(name = "occurred_at", nullable = false, updatable = false)  // 사건이 일어난 시각. 2년 보관 기준
    private LocalDateTime occurredAt;


    // 이력을 남길 때: 이 메서드로만 만들고, 만든 뒤에는 바꿀 수 없음 (F-10)
    public static InformationAccessLog create(Long permissionId, Long ownerUserId, Long actorUserId,
                                              Long informationId, InformationType informationType,
                                              AccessEventType eventType, LocalDateTime occurredAt) {
        InformationAccessLog log = new InformationAccessLog();

        log.permissionId = permissionId;
        log.ownerUserId = ownerUserId;
        log.actorUserId = actorUserId;
        log.informationId = informationId;
        log.informationType = informationType;
        log.eventType = eventType;
        log.occurredAt = occurredAt;

        return log;
    }
}