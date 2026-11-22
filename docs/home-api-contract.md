# GET /api/home — F-34 응답 계약

[이슈 #65](https://github.com/llszos/LIKELION_14th_2SWU_BE/issues/65) · [후속 PR #147](https://github.com/llszos/LIKELION_14th_2SWU_BE/pull/147) · [real API 홈 통합 조회](https://app.notion.com/p/4118b259919e81fb8015d556c1efb4df)

## 요청과 공통 응답

인증된 `GET /api/home`은 인증 정보의 사용자 ID로 조회한다. 공통 `ApiResponse.data`에 `counts`, `responseNeededItems`, `sentOpenItems`, `upcomingSchedules`, `situationCards`, `isNewUser`를 반환한다. 미인증·잘못된 토큰·비활성 계정은 공통 401로 처리하고, 의존 조회 실패는 공통 500으로 처리한다.

## 상단 집계

| 필드 | 집계 대상 |
| --- | --- |
| `counts.responseNeeded` | 로그인 사용자가 응답해야 하는 일반 응답과 재확인 요청 |
| `counts.unassigned` | 내가 보낸 요청 중 담당자가 없는 요청 |
| `counts.upcomingSchedules` | 내가 부탁했거나 맡은 수락·진행 중 일정 전체 |
| `counts.activeSharings` | 활성 공유 권한이 있는 요청·담당자 묶음. D 안심카드의 `sharingCount`와 같은 기준 |

기존 FE 연동을 위해 `counts.upcomingSchedules`와 `counts.activeSharings`를 유지한다. 일정 전체 건수는 `counts.upcomingSchedules`, 화면에 표시할 일정 최대 2개는 최상위 `upcomingSchedules` 배열에서 읽는다.

## 요청·일정 목록

| 목록 | 응답 필드 |
| --- | --- |
| `responseNeededItems[]` | `requestId`, `type`, `requestType`, `helpType`, `startsAt`, `place`, `requesterId`, `deadline`, `requesterName` |
| `sentOpenItems[]` | `requestId`, `requestType`, `helpType`, `startsAt`, `endsAt`, `requesterId`, `targetUserId`, `displayStatus`, `targetUserName`, `responseDeadline` |
| `upcomingSchedules[]` | `requestId`, `helpType`, `startsAt`, `place`, `requesterId`, `assigneeId`, `counterpartName` |

- `responseNeededItems[].type`은 일반 응답 `RESPONSE` 또는 재확인 `RECONFIRMATION`이며, `deadline`은 해당 응답의 마감 시각이다.
- 보낸 요청은 `WAITING`, `NO_ASSIGNEE`, `ACCEPTED`, `IN_PROGRESS`를 포함한다. `responseDeadline`은 `WAITING`일 때 현재 지정의 응답 마감 시각이며 그 외에는 null이다. `NO_ASSIGNEE`일 때 `targetUserName`은 null이다.
- `counterpartName`은 내가 부탁한 일정이면 담당자 이름, 내가 맡은 일정이면 요청자 이름이다. A 프로필을 일괄 조회하며, 표시 대상이나 프로필이 없으면 null, 탈퇴 프로필은 `탈퇴한 사용자`로 표시한다.
- C 조회 순서를 유지하고, 다가오는 일정은 시작 시각순으로 최대 2개를 반환한다. 빈 목록은 빈 배열이다. 날짜·시간은 서울 현지 시각을 오프셋 없이 반환한다.

## 상황 카드와 신규 사용자

- `situationCards.my`는 내가 생성한 진행 중 상황, `situationCards.participating`은 다른 사용자가 생성한 진행 중 상황이다. 두 배열 모두 조회자가 ACTIVE로 참여한 상황만 포함한다. 생성 시각과 ID 내림차순으로 반환한다.
- 카드의 필드는 `situationId`, `name`, `startAt`, `endAt`, `participantCount`, `isOwner`, `nextSchedule`, `remainingScheduleCount`, `requestCounts`다. `participantCount`는 ACTIVE 참여자 수다.
- `nextSchedule`은 가장 가까운 수락·진행 중 일정의 `startsAt`, `helpType`, `assigneeName`을 반환한다. 일정이 없으면 null이고, `remainingScheduleCount`는 첫 일정을 제외한 건수다.
- `requestCounts`는 `accepted`, `inProgress`, `waiting`, `noAssignee`, `responseNeeded`를 제공한다. 나의 상황 카드는 C Reader의 상황별 상태 집계를 사용한다. 참여 상황 카드의 수락·진행 중 건수와 일정은 내가 담당하는 요청만 포함하며, `waiting`과 `noAssignee`는 0이다.
- `responseNeeded`는 조회자의 일반 응답과 재확인을 상황별로 합산한다. #143의 `situationId`를 기준으로 집계하고 상황 ID가 없는 급한 요청은 카드 건수에서 제외한다.
- `isNewUser`는 상황을 생성하거나 참여한 이력이 한 번도 없을 때 true다. 종료·나가기·제외 이력이 있으면 현재 카드가 비어 있어도 false다.

## 연동과 검증

C의 `CareRequestSummaryReader`, D의 `SharingCountReader`, A의 `UserProfileQuery`를 사용한다. DB 스키마와 Flyway 마이그레이션 변경은 없다. 로컬에서는 홈 응답과 상황 조회에 대한 MySQL 통합 테스트를 포함해 관련 테스트 55개를 확인했다. 개발 서버 응답은 배포 후 검증한다.
