package com.gyeot.global.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 생성 일시(created_at)만 있는 테이블의 엔티티가 상속합니다.
 * 수정 일시(updated_at)도 있는 테이블은 BaseEntity를 상속합니다.
 * created_at은 저장 시 자동으로 채워지므로 직접 넣지 않습니다.
 */

@Getter // 조회용 getter만 생성 (값은 자동으로 채워지므로 setter는 두지 않음)
@MappedSuperclass   // 별도 테이블 없이, 상속한 엔티티의 테이블에 created_at 컬럼이 생김
@EntityListeners(AuditingEntityListener.class)  // 저장·수정할 때 일시 값을 자동으로 넣어 줌
public abstract class BaseCreatedEntity {   // 직접 생성 불가, 상속해서만 사용

    @CreatedDate    // 처음 저장할 때 현재 시각을 넣음
    @Column(name = "created_at", nullable = false, updatable = false)   // 빈 값 불가, 엔티티를 수정해도 생성 일시는 바뀌지 않음
    private LocalDateTime createdAt;
}