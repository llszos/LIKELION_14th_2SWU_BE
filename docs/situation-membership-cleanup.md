# A → B 상황 참여 정리 — 구현 범위와 리뷰 사항

## 기준

- develop `dc7664d02b4019984f5d50cd37262d34d3e924ae` 기반 별도 브랜치.
- A #128 `b8177e5`: RelationshipEndedEvent(relationshipId, sourceUserId, targetUserId, relationshipType, occurredAt).
  MY_PERSON은 source → target, GUARDIAN은 양방향 종료 후 이벤트 한 번. 보호자 전환에는 미발행.
- develop UserWithdrawalService는 계정 변경·세션 폐기와 같은 트랜잭션에서 UserWithdrawnEvent(userId, occurredAt) 발행.
- C #129 `c0ca4fb`: 참여 종료 이벤트의 userId는 사용자 PK. RELATIONSHIP_ENDED 사유로 요청·지정 정리.
- B 요구사항: https://app.notion.com/p/40b8b259919e818491d3fed09bcb8421
- ERD 9.6: https://app.notion.com/p/4098b259919e8050a531f3e54d6ab6cb

## 구현

- 동기 EventListener → MANDATORY Service. A 원본 트랜잭션 없이 단독 실행하지 않음. 실패 전파로 A/B/C/D의 원자적 롤백을 연결.
- MY_PERSON: source가 만든 ACTIVE 상황 중 ACTIVE OWNER가 존재하는 상황에서 target 일반 참여만 REMOVED.
- GUARDIAN: 두 사용자가 각각 만든 상황에서 반대 사용자를 동일하게 처리.
- 제3자가 만든 상황, OWNER, ENDED, LEFT/REMOVED는 변경하지 않음. 마지막 일반 참여자 제외도 상황 종료로 연결하지 않음.
- 실제 전이마다 SituationParticipantRemovedEvent(situationId, userId, RELATIONSHIP_ENDED, leftAt) 발행.
- 동기 정리는 잠금 후 현재 ACTIVE 행을 대상으로 한다. 참여 추가가 먼저 커밋되어 joinedAt이 occurredAt보다 늦으면 joinedAt을 leftAt으로 기록한다. 반복 수신은 최초 상태·시각을 보존한다.
- 동일 사용자 ACTIVE 행이 여러 개인 비정상 데이터는 상태 전이 전에 오류로 거부하여 이벤트 중복 발행을 피함.
- 탈퇴: 타인 ACTIVE 상황의 본인 일반 참여를 LEFT로 변경. B 참여 종료·상황 종료 이벤트는 발행하지 않음(C는 원본 탈퇴 이벤트 수신).
- 기존 SituationParticipant.remove()/leave(), A/B 이벤트 record 재사용. C·A 제품 코드, 기존 Repository, Entity, HTTP API, Flyway 마이그레이션 변경 없음. MySQL 통합 테스트는 test 프로필의 Flyway와 Hibernate validate를 사용.
- 전용 Repository에서 상황 ID 순서로 부모를 잠그고 참여 행 ID 순서로 잠금 조회. 기존 관리 객체는 flush → detach → 잠금 find로 현재 상태 재조회.

## 정책 보류

- 사용자 확인에 따라 OWNER 탈퇴 시 본인 상황 삭제/종료는 보류. OWNER 행도 변경하지 않음.
- ERD 9.6의 본인 상황 삭제와 C #129의 본인 요청 보존이 충돌. C/D 기록·FK·삭제 순서 합의 전까지 실행하지 않음.
- ENDED 상황의 탈퇴자 ACTIVE 참여 이력을 LEFT로 바꿀지는 별도 확인. 현재 종료 상황 변경 금지와 이력 보존에 따라 변경하지 않음.
- 과거 이벤트를 관계 재성립/재참여 이후 재생하는 범용 exactly-once inbox는 없음. 재전달을 도입하려면 관계 세대를 식별하는 계약이 필요하다.

## 테스트

- 단위: 방향·C 이벤트 필드·OWNER 보존·종료/이탈 상태·반복·후발 재참여·탈퇴·실패 전파.
- Spring: 실제 EventListener 라우팅·동기 실패 전파·MANDATORY 원본 트랜잭션 요구·호출자 롤백 전달.
- MySQL 8.4 Testcontainers: 관계 범위·양방향·반복·롤백/재시도·탈퇴·RR 기존 관리 객체·동시 중복(6개).
- DB 테스트는 Docker 없는 환경에서 성공으로 간주하지 않으며 disabled/skip 옵션을 추가하지 않음.
- 실행 결과와 환경 제한은 작업 보고에서 구분. 비DB 테스트는 실제 DB 롤백/잠금 또는 A/C/D 통합의 증거가 아님.

### 2026-11-17 로컬 실행 결과

- JDK 21.0.2 / Gradle 9.7.1, offline 실행.
- 최종 신규 테스트 14개 + 기존 Entity 15개 + 기존 Reader 10개 = 39개 통과. 실패/오류/스킵 0.
- main/test 전체 소스 컴파일 성공. 전체 테스트/전체 build는 실행하지 않음.
- PR 준비 단계에서도 `--rerun-tasks`로 컴파일과 같은 39개 테스트를 재실행하여 성공 확인.
- PR 준비 시 Docker CLI·기본 설치 경로·Docker Desktop/backend 실행 프로세스를 확인하지 못해 MySQL 테스트 6개 재실행은 보류. 설치·시스템 환경 변경은 하지 않음.
- MySQL 통합 테스트 실행 시도는 Docker 환경 탐색 실패로 initializationError 1건. 실제 테스트 메서드 6개는 미실행.
  증거: ignored `build/cleanup-verification/mysql-initialization-failure.xml`.
- Gradle 시작 시 Windows JDK Unix 소켓 오류가 발생하여, 테스트 프로세스에만 아래 옵션을 설정해 TCP fallback 사용.
  제품 코드·Gradle 설정·시스템 영구 설정은 바꾸지 않음.

```powershell
$env:JAVA_HOME='C:\Users\Administrator\.jdks\openjdk-21.0.2'
$env:GRADLE_USER_HOME='C:\Users\Administrator\.gradle'
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Users\Administrator\IdeaProjects\LIKELION_14th_2SWU_BE\build\unavailable-unix-socket-dir'
# 위 소켓 경로는 존재하지 않아 JDK가 TCP로 fallback합니다. 해당 디렉터리를 만들지 않습니다.
.\gradlew.bat --offline --no-daemon test --tests '*SituationMembershipCleanupServiceTest' --tests '*SituationMembershipCleanupListenerTest' --tests '*SituationMembershipCleanupTransactionTest' --tests '*SituationEntityTest' --tests '*SituationParticipantReaderImplTest'
# Docker 환경에서 별도 실행:
.\gradlew.bat --offline --no-daemon test --tests '*SituationMembershipCleanupIntegrationTest'
```

## 통합 전 확인

- #128 실제 관계 해제와 B 리스너, #129 실제 C 정리 및 D 권한 회수의 통합/실패 롤백 검증.
- A의 사용자·관계 잠금 → B 상황·참여 잠금 → C 요청·지정 → D 잠금 전체 순서 확인. 동기 리스너 순서 간 교착/재시도 검증 필요.
- #103 추가/제외·나가기와 동일 부모 잠금 규칙 통합. 신규 서비스는 #103 임시 HTTP 정책/Processor에 의존하지 않음.
- #118 생성/추가의 관계 검증과 관계 해제·탈퇴의 경합은 별도 통합 대상. 이 브랜치만으로 A 자격 검사 후 저장 경합을 모두 차단하지 않음.
- #94 종료와 동시 이벤트 처리, #116 시간 모델/Flyway 후 회귀. 제품 코드는 기간 필드에 의존하지 않지만 테스트 fixture는 현재 develop 날짜 생성 계약 사용.
- #95/#133에 직접 변경 없음. 이 브랜치로 F-04 전체 또는 F-22/F-34 완료를 선언하지 않음.
- PR은 확정된 일반 참여자 정리 범위의 코드 리뷰용이며, DB·전체 도메인 통합 검증 및 OWNER 정책 확정은 병합 전 별도 확인 대상.
