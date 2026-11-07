# 곁 — LIKELION_14th_2SWU_BE

곁은 친구·연인·이웃 등 신뢰하는 사람들과 필요한 정보와 도움을 주고받는 생활 안전망 서비스입니다. 사람 연결, 상황, 도움 요청, 요청에 따른 안심카드 정보 공유를 중심으로 개발합니다.

멋쟁이사자처럼 대학 14기 슈멋사 프로젝트, 2팀(2SWU) 백엔드 저장소입니다. 아래 기능은 개발 범위이며 전체 구현 완료를 뜻하지 않습니다.

## 목차

1. [팀원·역할](#팀원역할)
2. [기술 스택](#기술-스택)
3. [주요 기능](#주요-기능)
4. [공통 API 응답 형식](#공통-api-응답-형식)
5. [프로젝트 구조](#프로젝트-구조)
6. [로컬 실행 요약](#로컬-실행-요약)
7. [테스트](#테스트)
8. [협업 규칙](#협업-규칙)
9. [보안](#보안)

## 팀원·역할

| 담당자 | 영역 | 주요 담당 |
| --- | --- | --- |
| 최현아 | A. 회원·인증·관계 | 회원가입·로그인, 사람 초대, 보호자 관계, 비상연락망, 관계 해제 |
| 유미진 | B. 상황·알림 | 상황·참여자·종료, 지난 상황, 홈, 공통 응답·예외 처리, 문서·QA / SHOULD 알림함·감사 메시지 |
| 김유빈 | C. 도움 요청 | 요청 작성·응답·상태 관리, 중복 수락 방지, 수정·취소, 담당자 없음 안내 |
| 김민주 | D. 안심카드·정보 공유 | 안심카드, 정보 공개·권한·접근 이력, 공통 Entity 규칙, CI/CD·인프라 |

세부 담당 범위는 [백엔드 역할 분담](https://app.notion.com/p/4098b259919e80b58c9ffb324dddc1fb)을 따릅니다.

## 기술 스택

![Java 21](https://img.shields.io/badge/Java-21-437291)
![Spring Boot 4.1.1](https://img.shields.io/badge/Spring_Boot-4.1.1-6DB33F?logo=springboot&logoColor=white)
![Gradle 9.7.1](https://img.shields.io/badge/Gradle-9.7.1-02303A?logo=gradle&logoColor=white)
![MySQL 8.4](https://img.shields.io/badge/MySQL-8.4-4479A1?logo=mysql&logoColor=white)
![GitHub Actions](https://img.shields.io/badge/CI-GitHub_Actions-2088FF?logo=githubactions&logoColor=white)

| 구분 | 기술 |
| --- | --- |
| 웹·데이터 | Spring Web MVC, Spring Data JPA, MySQL |
| 인증·검증 | Spring Security, Validation, JJWT 0.13.0 |
| API 문서·개발 지원 | springdoc OpenAPI 3.1.1, Lombok |
| 테스트 | JUnit Platform, Spring Boot Test, Testcontainers |

버전과 의존성의 기준은 [build.gradle](build.gradle), [Gradle Wrapper](gradle/wrapper/gradle-wrapper.properties)입니다. 의존성이 있다고 해당 기능의 구현이 완료된 것은 아닙니다.

## 주요 기능

- **회원·연결:** 이메일 기반 인증, 사람 초대, 서로의 보호자 관계, 비상연락망.
- **상황:** 기간과 참여자를 묶는 상황 생성·관리·종료, 지난 상황과 홈 조회.
- **도움 요청:** 요청 작성, 대상자 응답·수락, 일정 변경 재확인, 취소·완료와 상태 관리.
- **안심카드:** 정보 항목 관리, 요청별 공개 항목 선택, 접근 권한 활성화·회수와 접근 이력.
- **확장 범위:** 알림·감사 메시지·급한 도움 요청 등 SHOULD 기능은 MUST 이후 진행합니다.

서비스 흐름, ERD 이미지, 주요 API 목록과 배포 안내는 개발 진행 후 검증된 내용으로 추가합니다. 정책·데이터 구조는 [기획안](https://app.notion.com/p/4098b259919e80b99b8ce12ad4092e11), [ERD](https://app.notion.com/p/4098b259919e8050a531f3e54d6ab6cb)를 참고합니다.

## 공통 API 응답 형식

[API 공통규칙](https://app.notion.com/p/4098b259919e8037adf6cc193b4638c5)에 따른 계약입니다. 각 API의 적용 여부는 해당 구현·PR에서 확인합니다.

```json
{
  "status": "success",
  "code": null,
  "message": null,
  "data": { "items": [] }
}
```

```json
{
  "status": "error",
  "code": "INVALID_INPUT",
  "message": "입력값이 올바르지 않습니다.",
  "data": null
}
```

- `status`, `code`, `message`, `data` 네 필드를 null까지 유지합니다.
- 실패 시 `data`는 null이며, 실제 HTTP 오류 상태를 사용합니다.
- 목록은 `data.items`로 감싸고 빈 목록은 빈 배열로 반환합니다.
- JSON key는 camelCase를 사용합니다. 날짜·시각은 API 공통규칙을 따릅니다.

## 프로젝트 구조

프로젝트 이름은 `gyeot`, 기본 패키지는 `com.gyeot`입니다. 아래는 영역별 구성 규칙이며 현재 존재하는 파일의 전체 목록이 아닙니다. 필요한 하위 패키지는 기능 구현 시 추가합니다.

| 경로 | 책임 |
| --- | --- |
| `global/common` | 공통 응답·공통 Entity 기반 |
| `global/config` | JPA Auditing·Swagger 등 공통 설정 |
| `global/exception` | 공통 오류 코드·업무 예외·전역 예외 처리 |
| `global/security` | 인증·인가. #33의 auth·jwt·refresh·cookie·cors·csrf 하위 영역 포함 |
| `domain/user`, `domain/relation` | A. 회원·인증·초대·관계·비상연락망 |
| `domain/situation`, `domain/home` | B. 상황·참여자·홈·알림 |
| `domain/carerequest` | C. 도움 요청·대상 지정·응답·변경 |
| `domain/safetycard`, `domain/access` | D. 안심카드 원본·권한·접근 이력 |
| `src/main/resources` | 프로필별 설정 |
| `src/test/java/com/gyeot` | 단위·통합 테스트, Testcontainers 설정 |

각 도메인에서는 `entity`, `repository`, `service`, `controller`, `dto/request`, `dto/response`, `query`, `event`로 책임을 나눕니다. 다른 영역의 테이블을 직접 변경하지 않고, 조회 계약과 도메인 이벤트로 연결합니다.

## 로컬 실행 요약

1. JDK 21과 Docker Desktop을 준비하고 IntelliJ의 Project SDK·Gradle JVM을 21로 맞춥니다.
2. [개발 환경 안내](docs/development.md)에 따라 MySQL을 준비합니다. local의 DB URL·계정·비밀번호는 설정 파일 기본값을 사용할 수 있습니다. **이미 기본값으로 만든 컨테이너는 DB 환경변수 없이 실행 가능**하며, 다른 비밀번호로 만들었다면 `DB_PASSWORD`를 설정합니다.
3. #33 인증 코드 적용 후에는 **Base64 32바이트 이상 난수 키인 `JWT_SECRET`이 필수**입니다. 누락·잘못된 값이면 서버가 시작되지 않습니다. [JWT·쿠키·CORS 설정](docs/development.md#인증-환경변수-pr-33-적용-후)을 먼저 확인합니다.
4. Windows PowerShell은 `.\gradlew.bat bootRun`, macOS/Linux는 `./gradlew bootRun`을 실행합니다.

[Swagger UI](http://localhost:8080/swagger-ui/index.html) · [프로필·환경변수·문제 해결](docs/development.md)

[인증 연동 문서](docs/authentication.md): 토큰·쿠키·CSRF·로그인 사용자 ID 사용법.

## 테스트

| 목적 | Windows PowerShell | macOS / Linux |
| --- | --- | --- |
| 테스트 | `.\gradlew.bat test` | `./gradlew test` |
| 전체 빌드 | `.\gradlew.bat build` | `./gradlew build` |

DB 통합 테스트는 Docker와 Testcontainers를 사용합니다. 환경 문제와 기능 실패를 구분하며, `build -x test` 성공을 테스트 통과로 기록하지 않습니다. [테스트 상세·환경 오류](docs/development.md#빌드와-테스트)를 참고합니다.

[CI](.github/workflows/ci.yaml)는 develop·main 대상 PR과 develop Push에서 전체 빌드를 실행합니다.

## 협업 규칙

1. Issue → develop 최신화 → 작업 브랜치 → 개발·Commit → PR → 리뷰 → develop 병합 순서로 진행합니다. 기본 단위는 1 Issue·1 Branch·1 PR입니다.
2. 브랜치는 `type/이슈번호-영문-kebab-case`, 커밋은 `type: 한글 작업 내용 (#이슈번호)`, PR 제목은 `[Type] 작업 내용`을 사용합니다.
3. [PR 템플릿](.github/pull_request_template.md)을 사용하고 관련 Issue·리뷰어를 지정합니다. CI 통과와 최소 1명 이상의 Approve를 원칙으로 합니다.
4. 수정 의견은 반영하거나 합의해 해결하고 GitHub의 Create a merge commit 방식으로 병합합니다. main·develop 직접 Push와 공유 브랜치 force push는 금지합니다.
5. 배포 PR과 선병합 예외는 팀장 책임·협업 가이드의 조건을 따릅니다. 주요 Entity·스키마·인증·권한·공통 응답은 사전 Approve가 필요합니다.

[협업 가이드](https://app.notion.com/p/4098b259919e80f79149e110d1ddd180) · [상세 협업 절차](docs/development.md#협업-상세)

## 보안

- 비밀번호·JWT 키·토큰·운영 접속값을 코드·문서·GitHub·Notion에 기록하지 않습니다.
- `.env`는 자동 로드되지 않습니다. 터미널 또는 IntelliJ Run Configuration에 필요한 값을 주입합니다.
- local의 DB 기본값은 로컬 개발용입니다. 운영에서는 별도 자격증명을 주입합니다.
- #33의 Refresh Token은 HttpOnly 쿠키로 전달합니다. HTTPS 운영 환경의 Secure·SameSite·Domain·CORS는 [개발 환경 안내](docs/development.md#인증-환경변수-pr-33-적용-후)와 배포 구조에 맞춥니다.
- 테스트 전용 키를 로컬·운영 키로 재사용하지 않고, 민감정보 원문을 로그·증빙에 남기지 않습니다.

[프로젝트 Notion](https://app.notion.com/p/4098b259919e8072a7c9f56aa43c977c) · [백엔드 초기 설정](https://app.notion.com/p/4098b259919e8020a5cde63bc926e326)
