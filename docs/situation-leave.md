# Issue #61 상황 나가기 준비 구현

## 현재 범위

기존 Processor·응답 DTO를 유지하고 PR #93의 Repository에 잠금 조회 메서드를 추가했다.
JpaSituationLeaveStore는 별도 Spring Data Repository가 아니라 기존 두 Repository에 연결하는 어댑터다.
Processor를 Spring Service로 등록하고 인증된 사용자 ID를 전달하는 Controller를 연결했다.
POST /api/situations/{situationId}/leave는 본문 없이 ApiResponse<SituationLeaveResponse>를 반환한다.
기존 엔티티·공통 예외·A/C 영역 코드는 변경하지 않았다.

## 정책과 구현 가정

Issue #61에는 POST /api/situations/{situationId}/leave, OWNER 보호, 상태·시각 기록 및 이벤트 연동이 명시되어 있다.
아래 반복 요청·이력 선택 정책은 확정 명세가 아니라 이번 작업에서 사용자와 합의한 구현 가정이다.

- ACTIVE 일반 참여자는 기존 leave(LocalDateTime)를 호출한다.
- LEFT·REMOVED 재요청은 기존 상태와 최초 leftAt을 반환한다. HTTP 연결 후 200으로 응답하고 이벤트는 추가 발행하지 않는다.
- 참여 이력 중 ACTIVE 행을 우선한다. ACTIVE가 둘 이상이면 DataIntegrityViolationException으로 처리한다.
  임의 선택·상태 변경·이벤트 발행은 하지 않는다. 기존 공통 핸들러에서는 500으로 처리된다.
- ACTIVE가 없으면 joinedAt DESC, id DESC 순서의 첫 행을 선택한다. 동일 joinedAt에서는 큰 PK를 우선한다.
- 상황 미존재 및 참여 이력 미존재는 공통 NOT_FOUND(404), OWNER는 공통 FORBIDDEN(403)을 재사용한다.
- 상황 잠금 → 참여 이력 잠금·선택 → OWNER 검사 → ENDED 검사 순서로 처리한다.
  이력이 없는 사용자는 ENDED여도 NOT_FOUND(404), OWNER는 FORBIDDEN(403)이다.
  일반 참여 이력이 있으면 ENDED는 SITUATION_ENDED(409)이며 LEFT·REMOVED 반복 요청도 거부한다.
- OWNER 403은 연결된 Notion 초안의 409와 다르며 이번 사용자 지시를 따른다.
- 성공 응답 DTO는 situationId, userId, status, leftAt이다. 입력 본문은 필요 없다.
- Clock 설정의 한국 시간을 사용하고 UserWithdrawalService와 동일하게 마이크로초로 절삭한다.
  이벤트 occurredAt은 실제 엔티티 leftAt과 동일하며 원인은 PARTICIPANT_LEFT이다.

## 초기 구현 당시 후속 계획 (아래 2026-11-15 진행 결과 참고)

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

## 후속 구현 및 검증 (2026-11-15)

- 기존 원격 #61 HEAD f21f25e에서 로컬 브랜치를 생성했다. 최신 origin/develop(534900e)을
  충돌 없이 --no-commit --no-ff 병합했다. 새 커밋은 없으며 병합 완료 전 상태다.
- 상황과 참여 이력을 PESSIMISTIC_WRITE로 조회한다. Store는 MANDATORY 트랜잭션을 요구하고,
  이미 로딩된 관리 엔티티도 refresh하여 최신 DB 상태를 반영한다. 동일 상황의 변경 작업은
  상황 → 참여 이력 잠금 순서를 맞춰야 한다. 다른 변경 API의 잠금 정책은 후속 협의 대상이다.
- 인증 ID는 AuthenticationPrincipal에서만 얻는다. 요청의 userId 파라미터로 바꿀 수 없다.
- OWNER 403, LEFT·REMOVED 반복 요청의 상태·최초 시각 보존과 추가 이벤트 미발행은 유지했다.
- 접근 이력이 없는 사용자가 ENDED 정보를 409로 받지 않도록 이력 확인을 먼저 수행한다.
- Gradle은 JDK 로컬 통신 오류(Unable to establish loopback connection)로 컴파일 전에 중단됐다.
  IPv4 및 --no-daemon 재시도에서도 동일했다.
- 기존 캐시의 jar를 사용한 javac 컴파일은 전체 main 및 situation 테스트 소스에 대해 성공했다.
  Gradle 도구용 구버전 slf4j-api는 직접 실행 클래스패스에서 제외했다.
- JUnit Launcher 직접 실행: Processor 16개, 실제 SecurityConfig 기반 Controller 6개,
  기존 Entity 15개, 총 37개 성공 / 0개 실패. Gradle 정규 실행 결과와는 구분한다.
- 통합 테스트 4개(저장·재시도, 동시 나가기, 동기 이벤트 실패 롤백, 기존 영속 상태 갱신)를 추가했다.
  Docker 환경을 찾지 못해 컨테이너 초기화가 실패했고 테스트 본문 4개는 모두 미실행이다.
  DB 잠금·롤백·동시성은 아직 검증 완료가 아니다.
- 임시 컴파일·실행 자료는 Git 제외 경로 build/manual에 있다.

Docker 및 Gradle 실행 환경 복구 후 아래를 실행한다.

```powershell
.\gradlew.bat test --tests com.gyeot.domain.situation.service.SituationLeaveProcessorTest --tests com.gyeot.domain.situation.controller.SituationLeaveControllerTest --tests com.gyeot.domain.situation.entity.SituationEntityTest --tests com.gyeot.domain.situation.service.SituationLeaveIntegrationTest
```

최종 명세 협의: OWNER 403 및 공통 NOT_FOUND 유지 여부, ENDED 409, 반복 요청 응답,
C의 PARTICIPANT_LEFT 수신·트랜잭션 정책. C 연동 구현은 이번 범위에 추가하지 않았다.
