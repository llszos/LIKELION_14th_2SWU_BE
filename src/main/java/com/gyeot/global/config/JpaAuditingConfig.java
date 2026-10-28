package com.gyeot.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * BaseCreatedEntity·BaseEntity의 일시 자동 입력을 켜는 설정입니다.
 * 메인 클래스에 두면 JPA를 쓰지 않는 테스트(@WebMvcTest 등)에서 오류가 날 수 있어 분리했습니다.
 */

@Configuration  // 애플리케이션이 시작될 때 읽는 설정 클래스
@EnableJpaAuditing  // 이게 있어야 @CreatedDate·@LastModifiedDate가 실제로 동작함 (없으면 일시가 비어 저장 오류)
public class JpaAuditingConfig {
}
