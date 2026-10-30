package com.gyeot.domain.safetycard.entity;

import com.gyeot.global.common.entity.BaseEntity;
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

/**
 * 정보 주인이 등록한 안심카드 항목 하나입니다. (ERD 4.10 safety_information 안심카드 정보)
 * 항목 1개 = 1행으로 저장합니다. (예: 복용약 2개면 2행)
 * 작성·수정·삭제는 정보 주인 본인만 할 수 있습니다.
 * 생성 일시·수정 일시는 BaseEntity가 자동으로 채웁니다.
 */

@Getter                                             // 조회용 getter만 생성 (값 변경은 아래 메서드로만)
@NoArgsConstructor(access = AccessLevel.PROTECTED)  // JPA가 DB 값을 객체로 만들 때 쓰는 빈 생성자. 밖에서 new로 빈 객체를 못 만들게 막음
@Entity                                             // 이 클래스가 DB 테이블과 연결된다는 표시
@Table(
        name = "safety_information",
        indexes = {
                // 내 안심카드 목록·분류별 항목 조회용 (ERD 6.4 주요 인덱스 후보)
                @Index(name = "idx_safety_information_owner_id_information_type", columnList = "owner_id, information_type")
        }
)
public class SafetyInformation extends BaseEntity {

    @Id                                                  // 기본 키(PK)
    @GeneratedValue(strategy = GenerationType.IDENTITY)  // MySQL AUTO_INCREMENT로 번호 자동 발급
    private Long id;

    @Column(name = "owner_id", nullable = false)  // 정보 주인 ID (users.id). 다른 도메인이라 엔티티가 아니라 ID 값만 저장
    private Long ownerId;

    @Enumerated(EnumType.STRING)                                      // Enum 이름(예: MEDICATION)을 문자열로 저장
    @Column(name = "information_type", nullable = false, length = 40) // 어떤 항목인지 (생년월일, 복용약 등)
    private InformationType informationType;

    @Column(name = "title", length = 150)  // 항목 이름 (예: 혈압약). 생년월일·혈액형·기타 메모처럼 이름이 없는 항목은 NULL
    private String title;

    @Column(name = "information_value", nullable = false, columnDefinition = "TEXT")  // 실제 항목 내용 (예: 아침 식후 1알)
    private String informationValue;

    // 밖에서는 이 메서드로만 새 항목을 만듦 (생성자 대신 이름으로 의도를 드러냄)
    public static SafetyInformation create(Long ownerId, InformationType informationType, String title, String informationValue) {
        SafetyInformation information = new SafetyInformation();

        information.ownerId = ownerId;
        information.informationType = informationType;
        information.title = title;
        information.informationValue = informationValue;

        return information;
    }

    // 이름과 내용만 수정할 수 있음. 주인과 항목 종류는 바꿀 수 없음 (다른 항목이 필요하면 새로 등록)
    public void update(String title, String informationValue) {
        this.title = title;
        this.informationValue = informationValue;
    }
}