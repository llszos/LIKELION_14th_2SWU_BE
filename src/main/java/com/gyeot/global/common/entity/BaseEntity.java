package com.gyeot.global.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.LocalDateTime;

/**
 * 생성 일시(created_at)와 수정 일시(updated_at)가 모두 있는 테이블의 엔티티가 상속합니다.
 * 두 값 모두 저장 시 자동으로 채워지므로 직접 넣지 않습니다.
 */

@Getter
@MappedSuperclass   // 별도 테이블 없이, 상속한 엔티티의 테이블에 created_at·updated_at 컬럼이 생김
public abstract class BaseEntity extends BaseCreatedEntity {    // created_at과 자동 입력 설정은 부모에서 물려받음

    @LastModifiedDate   // 처음 저장할 때 넣고, 엔티티 내용이 바뀌어 수정될 때마다 현재 시각으로 갱신함
    @Column(name = "updated_at", nullable = false)  // 빈 값 불가
    private LocalDateTime updatedAt;
}
