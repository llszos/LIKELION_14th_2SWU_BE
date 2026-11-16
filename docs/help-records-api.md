# 도움 기록 조회 API — Issue #64

## 구현 계약

`GET /api/help-records` — `Authorization: Bearer {accessToken}` 필요.
조회자 ID는 principal에서만 가져온다. `userId` query로 다른 사람 기록을 요청할 수 없다.

| query | 기본값 | 의미/검증 |
| --- | --- | --- |
| type | ALL | 정확히 ALL/GIVEN/RECEIVED. 빈 값·소문자·미지원 값은 400 INVALID_INPUT |
| personId | 없음 | 양수 Long. GIVEN에서는 요청자, RECEIVED에서는 기록상 담당자인 상대방. ALL에서는 C가 각 역할별 적용 |
| page | 0 | 0 이상의 int |
| size | 20 | 1 이상의 int. 별도 최대 크기는 확정하지 않았으며 FE/팀 확인 대상 |

정렬은 C 계약의 startsAt DESC, requestId DESC를 그대로 따른다.
시작 시각이 없는 급한 요청은 C 정렬에 따라 뒤로 간다. 클라이언트 sort는 API 계약이 아니다.
완료·취소·만료 포함 기준, 기록상 담당자, 본인 관련 기록 범위는 C Reader가 판정한다.
B는 C Entity/Repository를 조회하거나 그 규칙을 재구현하지 않는다.

```json
{
  "status": "success",
  "code": null,
  "message": null,
  "data": {
    "items": [{
      "requestId": 9,
      "situationId": 1,
      "helpType": "HOSPITAL",
      "startsAt": "2026-11-16T12:30:00",
      "requesterId": 20,
      "assigneeId": 10,
      "status": "COMPLETED",
      "role": "GIVEN"
    }],
    "page": 0,
    "size": 20,
    "hasNext": false
  }
}
```

- role은 C가 반환한 GIVEN/RECEIVED이며 ALL은 조회 필터에만 사용한다.
- 급한 요청의 situationId/startsAt, 담당자 없이 끝난 기록의 assigneeId는 null을 보존한다.
- 기록이 없으면 items=[], hasNext=false인 200 응답이다. 존재하지 않는 상대방도 C 조회 결과에 따른다.
- 인증 없는/유효하지 않은/탈퇴 계정의 요청은 보안 필터에서 401, 잘못된 query는 400.
- C 예외·null 반환은 500으로 처리하며 빈 성공 목록으로 숨기지 않는다.
- 장소·부탁 내용·안심카드·이름·프로필·상황명은 응답에 추가하지 않는다.
  탈퇴 상대방의 ID를 이름으로 대체하지 않으며, 이름이 필요하면 A 계약 확인 후 후속 작업한다.

## Notion 및 FE 계약 대조

확인한 자료:
- [Issue #64](https://github.com/llszos/LIKELION_14th_2SWU_BE/issues/64)
- [상위 API 명세](https://app.notion.com/p/4118b259919e80e6b300e29fe56c59ce)
- [B 도움 기록 API](https://app.notion.com/p/4118b259919e81fab1a7c514bebc39c1)
- [기획 피드백](https://app.notion.com/p/4098b259919e8050a095e09849f94417)
- [C #98](https://github.com/llszos/LIKELION_14th_2SWU_BE/pull/98), head 03b54df

수정일만으로 최신 계약을 판정하지 않고 상위 페이지의 링크·요청/응답·피드백을 대조했다.
상위 페이지는 GET /api/help-records를 명시한다. 상세 페이지는 '명세 초안'이며 아래 차이가 남는다.
어제 FE 피드백을 반영한 별도의 확정 응답 원문은 확인하지 못했고 Notion은 수정하지 않았다.
사용자 지시에 따라 확인 가능한 Issue/C 계약 기준으로 구현했다.

| Notion 초안 | 구현/C #98 | 후속 |
| --- | --- | --- |
| scheduledAt | startsAt | 사용자 지시에 따라 C 이름 사용. FE 문서 동기화 필요 |
| role 예시 없음 | GIVEN/RECEIVED 보존 | ALL 목록 표시 역할을 C 계약대로 노출 |
| SHOPPING 예시 | C HelpType 그대로, 장보기는 GROCERY | 예시 enum을 새로 만들지 않음 |
| situationId nullable 미표기 | 급한 요청은 situationId/startsAt null | null 응답 처리 확인 |
| 표시정보 구성 요구 | 계약에 있는 ID만 반환 | A 이름/프로필 및 상황명 계약 미확인, 임의 필드/값 없음 |

items/page/size/hasNext와 공통 ApiResponse 구조는 초안을 유지한다.
위 구현 계약을 FE 최종 합의 완료로 보고하지 않는다.

## 의존성과 검증

- 최신 확인 develop c374ae1에는 getHelpRecords가 없다. #98은 미병합이다.
- C 코드를 복사/병합하지 않고 #98 03b54df에서 feat/64-help-records-api를 분기했다.
- PR base는 feat/67-care-request-summary-reader. #98 대비 diff는 B 코드/테스트/이 문서뿐이다.
- #98 병합 후 담당자와 최신 develop 통합·base 변경·C Reader 실제 DB 검증을 진행해야 한다.
- stacked base는 현 workflow의 develop/main 대상 PR 조건에 포함되지 않아 자동 CI 실행을 기대하지 않는다.
- main/test 컴파일 및 비DB 테스트 50개 통과: Controller 23, Service 24, DTO 3.
  실제 SecurityConfig+JWT 필터, 실제 B Service, mock C Reader 조합을 포함한다.
- 최초 Controller 실행은 테스트용 RefreshTokenCookieService mock 누락으로 실패했고,
  테스트 구성 보완 후 50개 모두 통과했다. 제품 코드를 우회하거나 테스트를 삭제하지 않았다.
- Docker/MySQL 테스트 및 C 실제 DB 조회·최신 develop 전체 조합·FE 왕복은 미실행이다.
- 단위 테스트가 C 쿼리의 본인 범위·정렬·권한을 실제 DB에서 검증했다고 주장하지 않는다.

Related to #64
