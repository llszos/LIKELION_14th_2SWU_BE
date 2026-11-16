# #60 / #118 구현 및 병합 점검

2026-11-16, #118 `6d1f214` 및 #116 `3226b4e` 기준. PR 본문의 과거 미완료 설명 대신 실제 코드로 확인합니다.

## #60 필수 체크리스트

- POST /api/situations: 기존 SituationCreateController → Service → Writer 재사용.
- 생성자: 인증 principal ID 사용, ACTIVE OWNER 자동 저장. 클라이언트 creatorId로 대체하지 않음.
- 입력: 필수값·이름 길이·startAt < endAt·유형/OTHER 조건·참여자 null/중복/본인 검증.
- 선택 참여자: 사용자 계정 및 생성자 → 대상 ACTIVE 관계 검사, 빈 선택 허용.
- 저장: 상황·OWNER·선택 참여자를 하나의 Service 트랜잭션에서 처리.
- GET 목록: ACTIVE 상황 ∩ 조회자 ACTIVE 참여, EXISTS, createdAt DESC/id DESC, Slice 페이지네이션.
- GET 상세: 상황 ACTIVE와 조회자 ACTIVE 참여를 별도로 확인, 접근 불가·ENDED·미존재는 404.
- DTO/Validation/공통 응답·오류 및 단위·Mock MVC·보안·트랜잭션 경계 테스트 존재.
- 실제 MySQL 저장·조회·롤백·동시성과 전체 CI는 별도 검증 필요. Mock 트랜잭션 테스트는 실제 DB 롤백 증거가 아님.

## 확정된 상세 C 연동

원문: [이슈 #60](https://github.com/llszos/LIKELION_14th_2SWU_BE/issues/60),
[상황 상세 명세](https://app.notion.com/p/4118b259919e814c98b7c4c4534ca38b),
[생성 명세](https://app.notion.com/p/4118b259919e81aaaea1f8d185cf9a5e).

- 상세 requests는 기존 CareRequestSummaryReader.getSituationRequests 결과를 그대로 사용. 실제 조회자 ID 전달, C 순서/필터/targetUserId 의미 보존.
- activeRequestCount는 상황 전체 waitingCount + noAssigneeCount + acceptedCount + inProgressCount. 조회자에게 보이는 requests.size()로 계산하지 않음.
- 하나의 now로 두 Reader 호출. B 권한 검사 전에는 C를 호출하지 않음.
- C 구현체 없음·통계 누락·null 결과·호출 실패는 빈 목록/0건으로 숨기지 않음. 구현체 없는 상태에서도 기본 목록·생성 Bean은 구성 가능하지만 상세는 실패함.
- #98 구현체의 최종 develop 연동이 상세 정상 동작의 배포 선행 조건. 이번 작업은 C 코드를 복사하거나 수정하지 않음.
- OWNER=요청자 계약은 사용자 확인에 따름. Reader에는 실제 viewerId를 그대로 전달.
- 요청 시각은 startsAt/endsAt. scheduledAt이나 assigneeId로 임의 변환하지 않음.

## 보류: 명세상 제안/충돌 항목

- isOwner, activeParticipantCount, targetUserName은 상세 명세에서 제안으로 구분되어 있어 추가하지 않음.
- A #109 프로필 Map 계약은 이름이 최종 확정될 때 사용. 이번 상세 필드에는 이름이 없어 A 의존성 추가 없음.
- 목록 카드의 추가 A/C 필드는 확정 원문을 확인하지 못해 추가하지 않음. 목록 최대 페이지 크기도 임의 지정하지 않음.
- Notion 생성 명세는 상황 코드/기타 조건을 협의 중으로 쓰지만 현재 코드·기존 브랜치 문서는 HOSPITAL_VISIT/RECOVERY/ABSENCE/OTHER 및 기타 1~15자 승인으로 기록. 코드를 임의 변경하지 않고 FE 확인 필요.
- Notion에는 기존 DATE의 시작일 00시/종료일 다음 날 00시 합의가 기록되어 있으나 #116 검토 문서는 미확정으로 기록. 이번 작업에서 데이터 변환 규칙이나 Flyway 버전을 확정하지 않음.

## 병합·배포 차단

- #116 선행 미병합. 이후 #118 base를 develop으로 변경하고 최종 diff/CI 확인 필요.
- C #113/생성·상세의 startDate()/endDate()와 #116 startAt()/endAt()는 호환되지 않음. C 담당자 적응·통합 컴파일 필요.
- 기존 DB start_date/end_date NOT NULL은 새 INSERT를 막을 수 있음. start_at/end_at 및 custom_situation_type 누락도 prod validate 실패 원인.
- Flyway V1/V2 버전·날짜 변환·기간 CHECK 및 구 NOT NULL 호환 조치는 DB 담당자 확정 후 실제 MySQL에서 검증. 이번 작업은 DDL/마이그레이션 파일을 변경하지 않음.
- 자동 종료 #94, 지난 상황 #95, 참여자 #103/#115와 시간 모델·Reader·fixture 중첩 확인 필요.
- 관계 해제·탈퇴와 선택 참여자 저장 경합 및 A→B→C→D 연동은 별도 실제 DB 검증 필요.

## 이번 로컬 검증

- Java 21, Gradle `--offline --no-daemon test`로 상황 Entity/Reader, 생성 DTO/Service/Writer/유형/참여자 검증, 조회 Service, Mock MVC/보안, 기록용 트랜잭션/생성 흐름을 명시적으로 선택: 132개 통과, 실패·오류·스킵 0.
- C 결과 전달·상황 전체 합계·조회자별 목록 차이·권한 거부 시 C 미호출·C 부재/실패/null 결과 처리와 startsAt/targetUserId HTTP 계약을 보강.
- main/test 소스 컴파일과 git diff --check 성공. 변경한 DB 통합 테스트는 컴파일만 확인했으며 실행하지 않음.
- MySQL/Testcontainers, Flyway, 실제 DB 저장/롤백/경합, 전체 build 및 수정 후 원격 CI는 미검증. Docker 실행 없음.
