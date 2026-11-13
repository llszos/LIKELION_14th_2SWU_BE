# Issue #61 상황 나가기 준비 구현

## 현재 범위

Processor, 잠금 조회 계약, 응답 DTO, 단위 테스트만 구현한다. 실제 DB 접근 및 HTTP API는 아직 제공하지 않는다.
PR #93의 Repository·Reader를 복사하거나 수정하지 않는다. 기존 엔티티·공통 예외 처리도 수정하지 않는다.
Store 구현이 없는 상태에서 기동 오류가 발생하지 않도록 Processor는 Spring Bean으로 등록하지 않는다.
Controller 역시 DB 연결 이후 추가한다. 단순히 직접 생성한 Processor에는 @Transactional이 적용되지 않는다.

## 정책과 구현 가정

Issue #61에는 POST /api/situations/{situationId}/leave, OWNER 보호, 상태·시각 기록 및 이벤트 연동이 명시되어 있다.
아래 반복 요청·이력 선택 정책은 확정 명세가 아니라 이번 작업에서 사용자와 합의한 구현 가정이다.

- ACTIVE 일반 참여자는 기존 leave(LocalDateTime)를 호출한다.
- LEFT·REMOVED 재요청은 기존 상태와 최초 leftAt을 반환한다. HTTP 연결 후 200으로 응답하고 이벤트는 추가 발행하지 않는다.
- 참여 이력 중 ACTIVE 행을 우선한다. ACTIVE가 둘 이상이면 DataIntegrityViolationException으로 처리한다.
  임의 선택·상태 변경·이벤트 발행은 하지 않는다. 기존 공통 핸들러에서는 500으로 처리된다.
- ACTIVE가 없으면 joinedAt DESC, id DESC 순서의 첫 행을 선택한다. 동일 joinedAt에서는 큰 PK를 우선한다.
- 상황 미존재 및 참여 이력 미존재는 공통 NOT_FOUND(404), OWNER는 공통 FORBIDDEN(403)을 재사용한다.
- ENDED는 SITUATION_ENDED(409)로 처리한다. 참여 이력 조회보다 먼저 검사하므로 반복 요청도 거부한다.
- OWNER 403은 연결된 Notion 초안의 409와 다르며 이번 사용자 지시를 따른다.
- 성공 응답 DTO는 situationId, userId, status, leftAt이다. 입력 본문은 필요 없다.
- Clock 설정의 한국 시간을 사용하고 UserWithdrawalService와 동일하게 마이크로초로 절삭한다.
  이벤트 occurredAt은 실제 엔티티 leftAt과 동일하며 원인은 PARTICIPANT_LEFT이다.

## PR #93 병합 후 후속 작업

1. 최신 PR #93 병합 상태와 Repository를 확인하고 기존 Repository를 사용해 SituationLeaveStore를 구현한다.
   기존 Reader는 요약·존재 여부만 반환하므로 변경 작업에 사용하지 않는다. 중복 Repository를 생성하지 않는다.
2. 하나의 트랜잭션에서 상황 행을 먼저 비관적 쓰기 잠금으로 조회하고 해당 사용자 참여 이력도 잠금 조회한다.
   관리 엔티티와 최신 상태를 반환하고 커밋까지 잠금을 유지한다. 이미 로딩된 엔티티의 오래된 상태도 방지한다.
   나가기·상황 종료·제외·참여 추가의 잠금 순서를 검토하고 ACTIVE 중복 방지 DB 제약과 함께 검증한다.
3. Processor를 Spring Bean으로 등록하고 트랜잭션 프록시가 적용되는 경로로 연결한다.
   인증된 본인의 userId를 사용하는 Controller 및 HTTP·인증·예외 응답 테스트를 추가한다.
4. 실제 MySQL의 독립 트랜잭션 두 개로 동시 나가기, 최초 시각 유지, 커밋된 LEFT 및 이벤트 1회를 검증한다.
   종료와 나가기 경합, 재참여 이력, 다중 ACTIVE, 이벤트 발행 실패 시 롤백도 통합 검증한다.
5. C 이벤트 수신 규칙과 커밋 후 처리·롤백 처리를 확인한다. 현재 이벤트 발행은 기존 패턴의 트랜잭션 내 발행이다.

단위 테스트는 업무 분기와 이벤트 호출 횟수를 검증한다. DB 잠금, 실제 동시성, 트랜잭션 롤백 및 서버 기동 성공을 보장하지 않는다.

## 로컬 검증 결과 (2026-11-13)

PowerShell에서 설치된 JDK를 현재 프로세스에 지정한 뒤 실행했다.

```powershell
$env:JAVA_HOME = 'C:/Users/Multi_35/.jdks/openjdk-21.0.2'
.\gradlew.bat test --tests com.gyeot.domain.situation.service.SituationLeaveProcessorTest --tests com.gyeot.domain.situation.entity.SituationEntityTest --tests com.gyeot.global.GlobalExceptionHandlerTest
.\gradlew.bat test
```

- 관련 테스트: 50개 통과, 0개 실패 (Processor 14개, 엔티티 15개, 공통 예외 21개).
- 전체 테스트: 167개 중 154개 통과, 13개 실패.
- 실패는 Docker/Testcontainers 초기화 실패와 그에 따른 Spring 컨텍스트 로딩 실패이다.
  일부 통합 테스트는 initializationError로 집계되어 본문이 실행되지 않았다.
- 실패 대상: EnumColumnSchemaTest, GyeotApplicationTests, InvitationResponseConcurrencyIntegrationTest,
  RefreshTokenServiceIntegrationTest, AccessRetentionSchedulerRollbackTest, AccessRetentionSchedulerTest,
  CareRequestSharingReaderImplTest, CareRequestRepositoryTest.
- 기존 팀원 코드는 수정하지 않았다. 동시 나가기와 실제 DB 잠금 검증은 미실행 상태이다.
