# GET /api/home — B 구현 계약과 통합 조건

## 근거와 범위
- Issue #65: https://github.com/llszos/LIKELION_14th_2SWU_BE/issues/65
- Notion 홈 통합 조회(2026-11-15 FE 피드백): https://app.notion.com/p/4118b259919e81fb8015d556c1efb4df
- 해당 명세는 여전히 '명세 초안'이며 기존 JSON 예시는 FE 추가 제안을 반영하지 않았다고 명시한다.
- 이 변경은 기존 C/D 계약을 조합한 기본 홈 HTTP API이다. F-34 전체 화면 완성을 의미하지 않는다.

## 요청과 공통 응답
인증된 GET /api/home. 경로·쿼리·본문으로 사용자 ID를 받지 않고 principal의 userId만 사용한다.
공통 ApiResponse의 data 아래에 counts, responseNeededItems, sentOpenItems, upcomingSchedules를 반환한다.
미인증·잘못된 토큰·비활성 계정은 기존 보안 정책의 401이다.
C Reader는 필수 의존성으로 직접 주입한다. 구현 Bean이 없으면 기동에 실패한다.
C null 결과·C/D 조회 실패는 공통 500으로 전달하며 0이나 빈 배열로 숨기지 않는다.

## 집계 매핑
| 응답 | 원본과 범위 |
| --- | --- |
| counts.responseNeeded | C responseNeededCount. 응답 필요 목록 전체 수(재확인 포함 여부 등은 C가 판단) |
| counts.unassigned | C noAssigneeCount. 내가 보낸 요청 중 담당자 없음 |
| counts.upcomingSchedules | C upcomingCount. 내가 부탁했거나 맡은 수락됨·진행 중 요청 전체 수 |
| counts.activeSharings | D countActiveSharings(userId). ACTIVE 권한의 (요청, 담당자) 묶음 수 |

B는 현재 시각을 서울 LocalDateTime으로 C에 전달하며 조회 조건·저장소 쿼리를 중복 구현하지 않는다.
목록 최대 2개 제한으로 전체 예정 건수를 다시 계산하지 않는다.

## 목록의 실제 직렬화 필드
| 목록 | 필드 |
| --- | --- |
| responseNeededItems | requestId, type, requestType, helpType, startsAt, place, requesterId, deadline |
| sentOpenItems | requestId, requestType, helpType, startsAt, endsAt, requesterId, targetUserId, displayStatus |
| upcomingSchedules | requestId, helpType, startsAt, place, requesterId, assigneeId |

B 전용 응답 record로 C 결과를 필드별 매핑한다. 기존 JSON 필드명·타입·enum·nullable을 유지하며 Entity/민감정보를 추가하지 않는다.
- upcomingSchedules는 C 순서를 보존하여 앞의 최대 2개만 반환한다. 시작시각순 정렬 책임은 C에 있다.
- responseNeededItems/sentOpenItems 순서도 C 결과를 보존한다. B에서 재정렬·추가 제한하지 않는다.
- 목록은 null 대신 빈 배열, 실제 빈 집계는 0이다. 원본 필드의 null은 그대로 유지한다(place, targetUserId 등).
- 날짜는 LocalDateTime을 그대로 직렬화한다. 오프셋을 임의 추가하거나 날짜로 축약하지 않는다.
- responseNeededItems.type은 RESPONSE 또는 RECONFIRMATION이며 저장 status/displayStatus와 다르다.
- targetUserId를 hasAssignee로 간주하지 않는다.

## FE 초안과의 차이 / 합의 필요
- title, 사용자 이름·프로필, requester/target 객체, counterpartName은 제공하지 않는다. helpType과 사용자 ID가 실제 C 계약이다.
- 일정은 scheduledAt이 아닌 startsAt, 응답 기한은 responseDeadline이 아닌 deadline이다.
- sentOpenItems는 저장 status 대신 C의 displayStatus를 제공한다. 응답 필요 목록에는 displayStatus가 없으며 type을 제공한다.
- counts.upcomingScheduleCount 및 sharingCount로의 이름 변경은 미확정이다. 기존 counts.upcomingSchedules/activeSharings를 유지한다.
- FE가 위 실제 필드명·enum·null 정책을 수용하는지 A/B/C/D/FE 합의가 필요하다. 이 문서는 Notion을 확정 상태로 변경하지 않는다.

## F-34 화면 충족 상태
| 분류 | 항목과 조건 |
| --- | --- |
| 구현 | 기본 상단 집계, 응답 필요/보낸 요청/예정 일정, 실제 빈 목록·0 |
| FE 재사용 가능(선행 API 통합 필요) | #118 진행 중 상황 목록의 기본 situationId/name/type/status/startAt/endAt. D-day·진행 바는 FE 계산 가능 |
| 필수 누락(전체 화면 기준) | 나의/참여 상황 분리 카드, isOwner/참여자 수, 카드별 요청 통계·다음 일정·잔여 일정 수. #118 기본 목록만으로 모두 충족되지 않음 |
| 추가 협의 필요 | 카드 공통 DTO·소유/참여 분류·집계 범위, 신규 사용자 판정(isNewUser), A 사용자 표시명/탈퇴 처리, C 추가 필드 및 null 규칙 |

현재 Reader getSituationStats가 일부 카드 통계를 제공하더라도 사용자별 상황 소유/참여 분류 계약은 별도다.
추가 Repository나 다른 PR 제품 코드를 복사하여 임의로 화면 계약을 확정하지 않는다.
빈 배열/0 응답은 지원하지만 '최초 가입'과 '현재 활동 없음' 구분은 지원하지 않는다.

## 선행 PR과 병합 전 조건
- #98 C 실제 구현은 develop 병합 완료다. #133 전용 worktree에서 develop 8a7e7c91을 충돌 없이 통합하고 리뷰 수정을 검증했다.
  ObjectProvider 지연 조회를 제거하고 CareRequestSummaryReader를 직접 주입한다.
- C upcoming 계약은 최대 2개다. B의 limit(2)는 중복이지만 기존 HTTP 응답의 최대 크기 보장을 방어적으로 유지한다.
- D SharingCountReader 구현은 develop에 있으며 그대로 재사용한다.
- #116/#118의 Entity·Repository·Flyway 및 C 제품 코드를 변경하지 않았다.
  #116 시간 모델·마이그레이션과 #118 상황 목록은 상황 카드 통합 시 선행 확인 대상이다.
- B 테스트는 실제 보안 필터/Controller/Service와 Mock C/D/User를 연결한다.
  실제 #98 구현, D 쿼리, MySQL, 최종 FE 통합 검증은 별도이며 통과로 표시하지 않는다.
- 전체 F-34 완료 선언/Issue 자동 종료 전에 카드 및 신규 사용자 정책을 합의하고 누락을 구현해야 한다.
