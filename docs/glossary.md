# 곁 백엔드 용어집

ERD·역할 분담·API 공통규칙과 코드를 비교해 정리한 개발용 용어집입니다. 용어의 존재가 해당 기능의 구현 완료를 의미하지 않습니다.

구현 상태는 2026-10-30 조사 시점의 `origin/develop` 커밋 `a24826e`를 기준으로 합니다. **현재 코드**, **명세**, **미병합 작업 브랜치**, **미확정**을 구분합니다. 현재 코드로 표시한 항목도 클래스 존재를 뜻하며, API·업무 흐름 전체의 완료를 뜻하지 않습니다.

## 1. 핵심 용어

### 상황과 참여자

| 한국어 명칭 | 코드·DB 명칭 | 의미 | 혼동하기 쉬운 개념 | 근거·구현 상태 |
| --- | --- | --- | --- | --- |
| 상황 | `Situation` / `situations` | 정보 주인이 생성하는 기간 있는 돌봄 상황. 여러 일반 요청을 묶는 단위 | 개별 돌봄 요청과 다름 | ERD 4.4. 현재 develop에는 클래스 없음 |
| 상황 생성자·소유자 | `situations.creator_id` / `creatorId` | 상황을 만든 사용자. 소유권 판단의 최종 기준 | OWNER 참여 행과 일치해야 하며, 두 기록은 서로 대체하지 않음 | ERD 4.4·4.5 |
| 상황 유형 | `situations.situation_type` / `SituationType` | 상황을 분류하는 값 | 요청의 도움 유형인 `HelpType`과 별개 | ERD 7.2에 값 미정 명시. enum 미구현 |
| 상황 기간 | `start_date`, `end_date` / `LocalDate` | 상황의 시작·종료 날짜. 시작일은 종료일보다 늦을 수 없음 | 요청 일정의 시각, 실제 상황 종료 시각과 다름 | ERD 4.4·6.2·7.6 |
| 실제 상황 종료 시각 | `ended_at` / `endedAt` | 상황을 실제로 종료한 시각 | 예정된 `end_date`와 다름 | ERD 4.4·9.4 |
| 상황 참여자 | `SituationParticipant` / `situation_participants` | 특정 상황에 참여하는 사용자와 역할·참여 상태·시각을 연결하는 행 | 사용자 계정 자체가 아님. 참여만으로 일반 요청 생성이나 정보 열람이 허용되지 않음 | ERD 4.5. develop에는 없음. 준비 브랜치 클래스는 아직 JPA Entity가 아님 |
| 참여 역할 | `role` / `OWNER`, `PARTICIPANT` | 상황 생성자와 일반 참여자의 구분 | ACTIVE·LEFT·REMOVED는 역할이 아닌 참여 상태 | ERD 4.5·7.2. enum은 미병합 준비 브랜치에 존재 |
| 참여 상태 | `status` / `ACTIVE`, `LEFT`, `REMOVED` | 현재 참여·자진 이탈·제외 구분 | 상황 자체의 ACTIVE·ENDED와 별개 | ERD 7.2·9.4. enum은 미병합 준비 브랜치에 존재 |
| 참여·이탈 시각 | `joined_at`, `left_at` | 참여 시작과 참여 종료 시각 | 생성·수정 시각과 동일한 개념이 아님. 참여 테이블에는 created_at·updated_at 없음 | ERD 4.5 |

### 돌봄 요청과 응답

아래 요청 클래스·enum은 ERD와 C의 `feat/9-help-request` 등 작업 브랜치에서 확인한 명칭입니다. 기준 develop에는 아직 없습니다.

| 한국어 명칭 | 코드·DB 명칭 | 의미 | 혼동하기 쉬운 개념 | 근거·구현 상태 |
| --- | --- | --- | --- | --- |
| 돌봄 요청·도움 요청 | `CareRequest` / `care_requests` | 구체적인 도움을 부탁하는 단위 | 상황 자체와 다름. 대표 한국어 명칭은 미확정 사항 참고 | ERD 4.6, C 작업 브랜치 |
| 일반 요청 | `RequestType.GENERAL` / `request_type` | 상황·일정이 필요한 요청. 요청자는 상황 생성자와 같아야 함 | 급한 요청의 조건을 그대로 적용하지 않음 | ERD 4.6·6.3 |
| 급한 도움 요청·긴급 요청 | `RequestType.URGENT` / `request_type` | 상황·일정 없이 생성하는 SHOULD 요청. 상황 참여를 검증하지 않음 | 일반 요청과 다름. 문서·코드에는 주로 급한 요청으로 표기 | ERD 4.6·4.7, 역할 분담 F-13 |
| 도움 유형 | `HelpType` / `help_type` | 요청하는 도움의 종류 | `SituationType`을 대신하는 값이 아님 | ERD 7.2, C 작업 브랜치 |
| 요청자 | `requester_id` / `requesterId` | 도움을 부탁하는 정보 주체 | 일반 요청에서는 상황 생성자와 같음. 급한 요청에는 상황이 없음 | ERD 4.6 |
| 요청 대상자 | `target_user_id` / `targetUserId` | 해당 지정에서 부탁을 받은 사용자 | 수락 전 대상자는 담당자가 아님 | ERD 4.7 |
| 담당자 | 수락한 현재 유효 `RequestAssignment` | 현재 요청을 맡은 사용자 | 별도의 고정 사용자 역할이 아님. 현재 대상자·과거 담당 이력과 구분 | ERD 4.7·7.3 |
| 대상자 지정 | `RequestAssignment` / `request_assignments` | 요청 대상과 응답·기한·마감 이력을 관리하는 행 | 사용자 ID와 지정 행 ID는 다름. 대상 변경 시 새 지정 행 생성 | ERD 4.7, C 작업 브랜치 |
| 응답 | `response_status` | 가능·어려움·확인 후 답변 | 요청 자체의 ACCEPTED와 응답 AVAILABLE은 서로 다른 컬럼 | ERD 7.2·7.4 |
| 미응답 | `response_status = NULL` | 아직 응답하지 않은 상태 | 임의의 WAITING·PENDING 응답 enum을 만들지 않음 | ERD 7.4 |
| 응답 기한 | `response_deadline` | 현재 지정에 응답할 기한 | 일반 요청은 기한 경과만으로 지정이 영구 마감되지 않음. 급한 요청은 예외 | ERD 4.7·7.4 |
| 지정 마감 | `closed_at`, `close_reason` | 해당 대상 지정을 더 이상 유효하게 사용하지 않는 처리 | 요청 전체의 취소·완료와 별개 | ERD 4.7·9.1 |
| 담당자 없음 | 화면에서 계산하는 상태 | 유효 담당자·응답·기한을 기준으로 계산한 안내 | `care_requests.status`에 저장하는 별도 enum이 아님 | ERD 7.4, 역할 분담 F-14 |
| 변경 제안·재확인 | `RequestChangeProposal` / `request_change_proposals` | 수락 후 수정 사항을 담당자에게 재확인받는 절차 | 제안 상태와 요청 상태는 별개 | ERD 4.8·7.5, C 작업 브랜치 |

### 관계와 정보 공유

| 한국어 명칭 | 코드·DB 명칭 | 의미 | 혼동하기 쉬운 개념 | 근거·구현 상태 |
| --- | --- | --- | --- | --- |
| 사용자 | `User` / `users` | 계정과 사용자 식별 정보 | OWNER·요청자·담당자는 계정의 고정 역할이 아님 | ERD 4.1, 현재 코드 |
| 관계 | `Relationship` / `relationships` | 사용자 사이의 방향 있는 연결 | 관계·상황 참여·정보 권한은 서로 다른 데이터 | ERD 4.3, 현재 코드 |
| 나의 사람 | `RelationshipType.MY_PERSON` | source 사용자에서 target 사용자로 향하는 독립 관계 | A → B가 있다고 B → A가 자동 생성되는 것은 아님 | ERD 4.3, 현재 코드 |
| 서로의 보호자·상호 보호자 | `RelationshipType.GUARDIAN` | 상호 관계. DB에는 방향별 2행 저장 | 모든 정보의 상시 열람권이나 법적 보호자 자격을 뜻하지 않음 | ERD 4.3, 현재 코드 |
| 안심카드 정보 | `SafetyInformation` / `safety_information` | 정보 주인이 작성한 원본 항목과 값 | 공유 권한이나 조회 이력과 별개 | ERD 4.10, 현재 코드 |
| 정보 주인 | `safety_information.owner_id` | 안심카드 원본 정보의 소유자 | 요청의 대상자·담당자와 다름 | ERD 4.10, 현재 코드 |
| 정보 접근 권한 | `RequestInformationPermission` / `request_information_permissions` | 요청·대상자 지정·정보 항목을 연결하는 접근 권한 | 관계나 참여 행이 있다는 이유만으로 접근할 수 없음 | ERD 4.11·8.3, 현재 코드 |
| 공유 예정 | `PermissionStatus.PENDING` | 수락 전 준비된 권한 | 아직 정보를 열람할 수 있는 ACTIVE 권한이 아님 | ERD 4.11, 현재 코드 |
| 권한 활성화 | `PermissionStatus.ACTIVE` | 접근 가능 단계 | 실제 열람에는 사용자·지정·기간·원본 등 유효 조건도 통과해야 함 | ERD 4.11·8.3, 현재 코드 |
| 권한 회수·철회 | `PermissionStatus.REVOKED`, `revoked_at`, `revoke_reason` | 접근 권한을 종료하고 시각·사유를 기록 | 원본 정보 삭제와 다름. 재공유 시 새 권한 행 생성 | ERD 4.11·8.4, 현재 코드 |
| 접근 이력 | `InformationAccessLog` / `information_access_logs` | 권한 부여·조회·회수 사실의 기록 | 권한을 부여하는 데이터 자체가 아님 | ERD 4.12, 현재 코드 |

## 2. 도메인별 상태값

다음은 명세의 허용값입니다. 표에 있다는 이유만으로 해당 enum이 develop에 구현된 것은 아닙니다.

| 대상·컬럼 | 값 | 의미 | 구현 상태 |
| --- | --- | --- | --- |
| 상황 `situations.status` | `ACTIVE`, `ENDED` | 활성, 종료. 예정·진행은 날짜로 계산 | 명세 및 미병합 B 준비 enum |
| 참여 역할 `situation_participants.role` | `OWNER`, `PARTICIPANT` | 소유자, 일반 참여자. 상태값과 별개 | 명세 및 미병합 B 준비 enum |
| 참여 `situation_participants.status` | `ACTIVE`, `LEFT`, `REMOVED` | 참여, 자진 이탈, 제외 | 명세 및 미병합 B 준비 enum |
| 요청 유형 `care_requests.request_type` | `GENERAL`, `URGENT` | 일반, 급한 요청. 상태값과 별개 | 명세 및 C 작업 브랜치 |
| 요청 `care_requests.status` | `REQUESTED`, `ACCEPTED`, `IN_PROGRESS`, `COMPLETED`, `CANCELED`, `EXPIRED` | 요청됨, 수락됨, 진행 중, 완료, 취소, 만료 | 명세 및 C 작업 브랜치 |
| 응답 `request_assignments.response_status` | `AVAILABLE`, `UNAVAILABLE`, `CHECK_LATER` | 가능, 어려움, 확인 후 답변. 미응답은 NULL | 명세 및 C 작업 브랜치 |
| 변경 제안 `request_change_proposals.status` | `PENDING`, `ACCEPTED`, `REJECTED`, `INVALIDATED` | 대기, 수락, 거절, 무효화 | 명세 및 C 작업 브랜치 |
| 관계 `relationships.status` | `ACTIVE`, `ENDED` | 활성, 종료 | 명세 및 현재 코드 |
| 권한 `request_information_permissions.status` | `PENDING`, `ACTIVE`, `REVOKED` | 공유 예정, 접근 가능 단계, 접근 종료 | 명세 및 현재 코드 |

같은 `ACTIVE`라도 **상황의 활성**, **사용자의 상황 참여**, **정보 접근 권한의 활성**을 각각 뜻합니다. 한 상태를 보고 다른 도메인의 상태를 추정하지 않습니다.

요청의 `COMPLETED`, `CANCELED`, `EXPIRED`는 종료 상태입니다. 상태 전이 규칙의 전체 내용은 ERD 7.3을 따릅니다. 화면 문구와 DB enum을 임의로 일대일 대응시키지 않습니다.

## 3. 혼동하기 쉬운 개념

| 구분 | 차이 |
| --- | --- |
| 상황 vs 돌봄 요청 | 상황은 기간과 참여자를 묶는 맥락이고, 요청은 구체적인 도움 한 건 |
| 생성자 vs OWNER 참여 행 | 소유권의 최종 기준은 creator_id. OWNER 참여 행은 같은 사용자를 가리켜야 함 |
| 역할 vs 상태 | OWNER도 역할이고 ACTIVE는 참여 상태. 서로 다른 컬럼에서 관리 |
| 참여 vs 정보 접근 | ACTIVE 참여자라도 유효한 정보 권한·지정·기간 등의 검증이 별도로 필요 |
| 대상자 vs 담당자 | 부탁을 받은 대상자가 수락하고 현재 지정이 유효해야 담당자로 판단 |
| 관계 vs 권한 | MY_PERSON·GUARDIAN 관계가 모든 정보 열람을 허용하지 않음 |
| 상황 유형 vs 도움 유형 | SituationType은 미정. HelpType의 HOSPITAL·MEAL·GROCERY·PET_CARE·CHECK_IN·OTHER를 대신 넣지 않음 |
| 응답 기한 경과 vs 지정 마감 | 일반 요청은 기한 경과 후에도 현재 대상이며 시작 전이면 가능으로 바꿀 수 있음. 급한 요청은 별도 정책 |
| 요청 취소 vs 권한 회수 | C의 요청 상태 변경과 D의 권한 상태 변경은 별개. 역할 분담상 후속 이벤트로 연결 |
| 예정 종료일 vs 실제 종료 시각 | end_date는 DATE, ended_at은 실제 종료 DATETIME(6) |
| API `status` vs 도메인 `status` | 공통 응답의 success·error는 처리 결과. 도메인 status는 ACTIVE·ENDED 등 업무 상태 |
| 오류 `code` vs HTTP 상태 코드 | INVALID_INPUT 등은 오류 식별 문자열, 400·404·500 등은 HTTP 응답 상태. 둘을 함께 사용 |
| 도메인 enum vs API 예시 | API 규칙의 WAITING 예시는 URI·JSON 설명용. 실제 요청 상태 enum으로 추가하지 않음 |

### API 응답 용어

| 명칭 | 코드·JSON 명칭 | 의미 | 근거·구현 상태 |
| --- | --- | --- | --- |
| 공통 응답 | `ApiResponse` | JSON API 데이터를 감싸는 응답 형식 | API 공통규칙. develop에는 아직 없음, 공통 API 작업 브랜치·PR #23 |
| 처리 결과 | `status` | 성공은 success, 실패는 error | API 공통규칙. 도메인 상태와 다름 |
| 오류 식별 코드 | `code` | 실패 종류를 구분하는 문자열. 성공 시 null | API 공통규칙 |
| 안내 문구 | `message` | 안내·오류 문구. 필요 없으면 null | API 공통규칙 |
| 실제 데이터 | `data` | 반환 데이터. 데이터 없음·실패 시 null | API 공통규칙 |
| 목록 항목 | `data.items` | 목록은 객체 안의 배열로 제공. 결과가 없으면 빈 배열 | API 공통규칙 |
| HTTP 상태 | 200, 201, 400, 401, 403, 404, 409, 500 등 | 통신 응답의 성공·실패 분류 | API 공통규칙. 실패를 HTTP 200으로 숨기지 않음 |

JSON key는 camelCase, DB 컬럼은 snake_case를 사용합니다. API 날짜·시각은 KST 기준이며 시각 문자열에는 시간대 표기를 붙이지 않는 규칙입니다. DB의 상황 기간은 DATE, 시각은 Asia/Seoul 기준 DATETIME(6)입니다.

## 4. 미확정 사항 및 출처

### 미확정·후속 확인

- **SituationType**: ERD 7.2에 값 미정으로 표시되어 있습니다. 실제 enum 값은 추가하지 않습니다.
- **돌봄 요청 / 도움 요청**: 문서와 코드 설명에서 혼용됩니다. `CareRequest`, `care_requests`를 기준으로 연결하되 대표 한국어 명칭은 팀 확인이 필요합니다.
- **급한 요청 / 긴급 요청**: 코드·명세의 URGENT는 확인됐지만 대표 한국어 명칭은 확인이 필요합니다. 이 문서는 원문에서 주로 쓰는 급한 요청을 함께 표기합니다.
- **참여 종료 이벤트 사유**: 미병합 `SituationParticipantRemovedEvent`는 참여 종료를 알리는 임시 계약입니다. LEFT·REMOVED 및 관계 해제 원인의 전달 방식은 C 담당자 확인 전까지 미확정입니다. ERD의 참여 상태값 자체가 미정이라는 뜻은 아닙니다.
- **조회 결과 상태 타입**: 미병합 `SituationParticipantReader.SituationSummary.status`는 브랜치 의존성을 피하기 위해 임시 String입니다. 상황 enum 병합 후 SituationStatus 전환 TODO가 있습니다.
- **기능명세 원문 접근 제한**: Notion의 1차 기능명세서 페이지는 열렸으나 연결된 실제 원문은 NOT_FOUND로 읽지 못했습니다. 이 문서의 기능 설명은 ERD·역할 분담·API 규칙 및 코드로 확인한 내용이며, 원문을 직접 검증한 것으로 표시하지 않습니다.

### 출처

- [ERD 및 데이터 모델](https://app.notion.com/p/4098b259919e8050a531f3e54d6ab6cb): 4.3~4.12 테이블 정의, 7.2 상태·유형, 7.3~7.6 상태·시간 규칙, 8~9 정보 접근·종료 처리
- [백엔드 역할 분담](https://app.notion.com/p/4098b259919e80b58c9ffb324dddc1fb): 영역별 책임과 이벤트 연동
- [API 공통규칙](https://app.notion.com/p/4098b259919e8037adf6cc193b4638c5): 응답·HTTP 상태·JSON 규칙
- [1차 기능명세서 연결 페이지](https://app.notion.com/p/4098b259919e806c92d5e1edaa20dd38): 연결된 실제 원문은 접근 불가
- 현재 코드: [Relationship](../src/main/java/com/gyeot/domain/relation/entity/Relationship.java), [SafetyInformation](../src/main/java/com/gyeot/domain/safetycard/entity/SafetyInformation.java), [RequestInformationPermission](../src/main/java/com/gyeot/domain/access/entity/RequestInformationPermission.java), [PermissionStatus](../src/main/java/com/gyeot/domain/access/entity/PermissionStatus.java)
- 조사한 미병합 작업 브랜치: C의 `feat/9-help-request`, B의 `feat/situation-entity-preparation`, `feat/situation-domain-events`, `feat/situation-participant-reader`, `feat/22-common-api-response`

Notion 문서는 접근 권한이 필요할 수 있습니다. 원본 명세나 병합된 코드가 바뀌면 이 용어집의 기준 커밋과 구현 상태도 함께 갱신합니다.
