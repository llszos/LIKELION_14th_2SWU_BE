# 곁 — LIKELION_14th_2SWU_BE

멋쟁이사자처럼 대학 14기 슈멋사 프로젝트, 2팀(2SWU) 백엔드 저장소입니다.

곁은 친구·연인·이웃 등 신뢰하는 사람들과 필요한 정보와 도움을 주고받을 수 있도록 준비하는 생활 안전망 서비스입니다. 사람 연결, 돌봄 상황, 도움 요청, 요청에 따른 정보 공유를 중심으로 개발합니다.

현재 `develop`에는 개발 환경 설정과 일부 Entity·Repository가 준비되어 있습니다. 아래 기능과 역할은 개발 범위를 설명하며, 인증·상황·돌봄 요청 API가 모두 구현되었다는 의미는 아닙니다. 작업 브랜치의 미병합 변경은 현재 패키지 구조에 포함하지 않습니다.

## 목차

1. [기술 스택](#기술-스택)
2. [개발 환경 준비](#개발-환경-준비)
3. [로컬 실행](#로컬-실행)
4. [환경변수와 프로필](#환경변수와-프로필)
5. [빌드와 테스트](#빌드와-테스트)
6. [패키지 구조](#패키지-구조)
7. [팀 역할](#팀-역할)
8. [Git·PR 협업 규칙](#gitpr-협업-규칙)
9. [관련 문서](#관련-문서)

## 기술 스택

현재 `develop`의 [build.gradle](build.gradle), [settings.gradle](settings.gradle), [Gradle Wrapper](gradle/wrapper/gradle-wrapper.properties)와 테스트 설정을 기준으로 합니다.

| 구분 | 기술·버전 | 용도 |
| --- | --- | --- |
| 언어 | Java 21 | 애플리케이션 개발·실행 |
| 프레임워크 | Spring Boot 4.1.1 | 백엔드 애플리케이션 |
| 빌드 | Gradle Wrapper 9.7.1, Groovy DSL | 공통 빌드 환경 |
| 웹 | Spring Web MVC | HTTP API |
| 데이터 | Spring Data JPA, MySQL 드라이버 | 데이터 저장·조회 |
| DB 환경 | MySQL 8.4 | 로컬·Testcontainers 테스트 DB |
| 보안·검증 | Spring Security, Validation | 인증·인가 기반, 입력 검증 |
| JWT 라이브러리 | JJWT 0.13.0 | 토큰 기능 구현을 위한 의존성 |
| API 문서 | springdoc OpenAPI 3.1.1 | Swagger UI |
| 반복 코드 지원 | Lombok | getter·생성자 등 생성 |
| 테스트 | JUnit Platform, Spring Boot Test, Testcontainers | 단위·통합 테스트 기반 |
| CI | GitHub Actions | PR 및 develop 변경의 빌드·테스트 |

의존성 설정은 기능 구현 완료를 의미하지 않습니다. 프로젝트 이름은 `gyeot`, 기본 패키지는 `com.gyeot`입니다.

## 개발 환경 준비

- JDK 21을 설치하고 IntelliJ의 **Project SDK와 Gradle JVM을 모두 JDK 21**로 설정합니다.
- 저장소를 Clone한 뒤 Gradle을 동기화합니다. 별도로 Spring Initializr 프로젝트를 만들 필요는 없습니다.
- Docker Desktop을 설치하고 실행합니다. macOS는 Apple Silicon·Intel 환경에 맞는 버전을 사용합니다.
- 개인 PC의 별도 Gradle 대신 저장소의 Wrapper를 사용합니다.
- Lombok 오류가 있으면 Gradle 동기화와 IDE의 Lombok·Annotation Processing 설정을 확인합니다.

```text
java -version
javac -version
docker version
```

`java`, `javac`는 21인지, Docker는 서버에 연결되는지 확인합니다. 아래 명령은 저장소 최상위 폴더에서 실행합니다.

## 로컬 실행

### 1. 로컬 DB 비밀번호 준비

비밀번호를 README나 명령어에 직접 적지 않고, 현재 터미널의 `DB_PASSWORD` 환경변수로 전달합니다. DB 생성과 서버 실행은 같은 터미널에서 진행합니다.

**Windows PowerShell**

```powershell
$localDbPassword = Read-Host '로컬 DB 비밀번호' -AsSecureString
$env:DB_PASSWORD = [System.Net.NetworkCredential]::new('', $localDbPassword).Password
```

**macOS / Linux — Bash 또는 Zsh**

```bash
printf '로컬 DB 비밀번호: '
read -r -s DB_PASSWORD
printf '\n'
export DB_PASSWORD
```

비밀번호는 비어 있지 않게 입력합니다. 이 설정은 현재 터미널과 여기서 실행한 프로세스에 적용됩니다. IntelliJ 실행 버튼을 사용할 때는 Run Configuration의 환경변수에도 같은 `DB_PASSWORD`를 설정해야 합니다.

### 2. 로컬 DB 생성 — 최초 1회

**Windows PowerShell**

```powershell
docker run --name gyeot-mysql --env "MYSQL_ROOT_PASSWORD=$env:DB_PASSWORD" --env MYSQL_DATABASE=gyeot --env TZ=Asia/Seoul -p 127.0.0.1:3307:3306 -v gyeot-mysql-data:/var/lib/mysql -d mysql:8.4
```

**macOS / Linux — Bash 또는 Zsh**

```bash
docker run --name gyeot-mysql --env "MYSQL_ROOT_PASSWORD=$DB_PASSWORD" --env MYSQL_DATABASE=gyeot --env TZ=Asia/Seoul -p 127.0.0.1:3307:3306 -v gyeot-mysql-data:/var/lib/mysql -d mysql:8.4
```

호스트 포트는 기존 로컬 설정과 같은 `3307`을 사용하며, 컨테이너 데이터는 `gyeot-mysql-data` 볼륨에 보관합니다. MySQL이 연결을 받을 준비가 된 뒤 서버를 실행합니다.

컨테이너를 이미 만든 경우 다시 생성하지 않습니다. 재부팅 후에는 다음 명령으로 시작합니다.

```text
docker start gyeot-mysql
```

기존 컨테이너·볼륨을 사용할 때는 DB 생성 당시의 비밀번호를 입력해야 합니다. 환경변수 값을 바꾸는 것만으로 기존 DB 비밀번호가 변경되지는 않습니다. 문제 해결을 위해 기존 데이터나 볼륨을 임의로 삭제하지 않습니다.

### 3. 서버 실행

**Windows PowerShell**

```powershell
.\gradlew.bat bootRun
```

**macOS / Linux**

```bash
./gradlew bootRun
```

IntelliJ에서는 `GyeotApplication` 실행 버튼으로 시작할 수 있습니다. 로컬 DB와 실행 환경변수가 먼저 준비되어 있어야 합니다.

- 서버 시작 로그와 포트를 확인합니다.
- 기본 로컬 Swagger 경로: [Swagger UI](http://localhost:8080/swagger-ui/index.html)
- 초기 상태에는 루트 경로에서 404가 나와도 서버가 정상 실행 중일 수 있습니다. 아직 해당 Endpoint가 없기 때문입니다.
- macOS/Linux에서 Wrapper 실행 권한 오류가 발생하면 `chmod +x gradlew`를 실행한 뒤 다시 시도합니다.

## 환경변수와 프로필

변수 이름은 [.env.example](.env.example)을 참고합니다. 실제 비밀번호·Secret·운영 접속값은 README, GitHub, Notion에 기록하지 않습니다.

| 변수 | 용도 | 현재 코드 기준 |
| --- | --- | --- |
| `DB_URL` | DB 연결 URL | local은 기본값 사용 가능, prod는 필수 |
| `DB_USERNAME` | DB 계정 | local은 기본값 사용 가능, prod는 필수 |
| `DB_PASSWORD` | DB 비밀번호 | 위 로컬 실행에서는 직접 설정, prod는 필수 |
| `JWT_SECRET` | JWT용 Secret 준비 항목 | 예시 파일에는 있으나 현재 develop의 사용 코드는 미구현 |

**`.env` 파일은 자동으로 로드되지 않습니다.** 파일을 복사하는 것만으로 설정이 적용되지 않으므로, 터미널 환경변수 또는 IntelliJ Run Configuration에서 주입합니다. `.env`·`application-secret.yaml`은 Git 제외 대상입니다.

| 설정 파일 | 용도 | 스키마 관리 |
| --- | --- | --- |
| [application.yaml](src/main/resources/application.yaml) | 공통 설정, 기본 프로필 `local` | — |
| [application-local.yaml](src/main/resources/application-local.yaml) | 로컬 개발 | `ddl-auto: update` |
| [application-test.yaml](src/main/resources/application-test.yaml) | 자동 테스트 | `ddl-auto: create-drop` |
| [application-prod.yaml](src/main/resources/application-prod.yaml) | 배포 환경 | `ddl-auto: validate` |

테스트 DB 연결은 Testcontainers가 제공합니다. 운영은 `validate`이므로 테이블을 자동 생성하지 않으며, 운영 스키마 준비는 팀의 배포 절차를 따릅니다.

현재 코드와 ERD의 시각 기준은 `Asia/Seoul`입니다. 날짜는 `DATE`, 시각은 `DATETIME(6)`을 기준으로 설계하며, JVM·Hibernate·테스트에도 한국 시간대 설정이 있습니다.

## 빌드와 테스트

| 목적 | Windows PowerShell | macOS / Linux |
| --- | --- | --- |
| 테스트 실행 | `.\gradlew.bat test` | `./gradlew test` |
| 테스트 포함 전체 빌드 | `.\gradlew.bat build` | `./gradlew build` |
| 테스트 제외 빌드 | `.\gradlew.bat build -x test` | `./gradlew build -x test` |

현재 `GyeotApplicationTests`는 Testcontainers로 MySQL 8.4를 실행하므로 **Docker가 필요합니다**. 개발용 DB 컨테이너와 별도로 테스트용 컨테이너를 생성합니다.

`Could not find a valid Docker environment`가 나오면 Docker 설치·실행 및 연결 상태를 확인합니다. 개발용 MySQL을 시작하는 것만으로 Testcontainers 실행 조건이 충족되는 것은 아닙니다.

`build -x test`는 컴파일·JAR 생성을 확인하는 명령이며, **테스트를 통과했다는 의미가 아닙니다**. 전체 검증에는 Docker가 준비된 환경에서 `build`를 실행해야 합니다. 실제 확인한 결과만 PR에 기록합니다.

CI는 [ci.yaml](.github/workflows/ci.yaml)에 정의되어 있으며 `develop`·`main` 대상 PR과 `develop` Push에서 `./gradlew build`를 실행합니다.

## 패키지 구조

현재 `develop` 기준입니다. `situation`, `carerequest`, `home`, `global.exception`에는 아직 `.gitkeep`만 있습니다.

```text
src/main/java/com/gyeot
├── GyeotApplication.java
├── global
│   ├── common/entity     # 공통 생성·수정 시각
│   ├── config            # JPA Auditing·Swagger 설정
│   ├── exception         # 예외 처리용 자리
│   └── security          # 보안 기본 설정
└── domain
    ├── user/entity
    ├── relation/entity
    ├── safetycard
    │   ├── entity
    │   └── repository
    ├── access
    │   ├── entity
    │   └── repository
    ├── situation         # 상황 도메인 자리
    ├── carerequest       # 돌봄 요청 도메인 자리
    └── home              # 홈 도메인 자리

src/main/resources        # 프로필별 설정
src/test/java/com/gyeot    # 애플리케이션 테스트·Testcontainers 설정
```

`global`은 공통 기반 코드를, `domain`은 비즈니스 영역별 코드를 관리합니다. 상세 테이블·제약조건은 ERD 문서를 기준으로 확인합니다.

## 팀 역할

다음은 구현 완료 목록이 아닌 담당 범위입니다. SHOULD 기능은 MUST 이후 진행합니다.

| 담당자 | 영역 | 주요 담당 |
| --- | --- | --- |
| 최현아 | A. 회원·인증·관계 | 회원가입·로그인, 사람 초대, 보호자 관계, 비상연락망, 관계 해제 |
| 유미진 | B. 상황·알림 | 상황·참여자·종료, 지난 상황, 홈, 공통 응답·예외 처리, 문서·QA / SHOULD 알림함·감사 메시지 |
| 김유빈 | C. 돌봄 요청 | 요청 작성·응답·상태 관리, 중복 수락 방지, 수정·취소, 담당자 없음 안내 |
| 김민주 | D. 안심카드·정보 공유 | 안심카드, 정보 공개·권한·접근 이력, 공통 Entity 규칙, CI/CD·인프라 |

다른 영역의 테이블을 직접 수정하지 않습니다. 영역 간 변경 연동은 팀 이벤트 규칙을, 조회는 합의된 조회 계약을 따릅니다. 세부 범위는 역할 분담 문서를 확인합니다.

## Git·PR 협업 규칙

상세 기준과 예외는 [백엔드 협업 가이드](https://app.notion.com/p/4098b259919e80f79149e110d1ddd180)를 따릅니다.

1. **Issue 생성 → develop 최신화 → 작업 브랜치 → 개발·Commit → PR → 리뷰 → develop 병합** 순서로 진행합니다. 기본 단위는 **1 Issue → 1 Branch → 1 PR**입니다.
2. 브랜치 형식은 `{type}/{issue-number}-{description}`입니다. 설명은 영문 소문자·kebab-case를 사용하며, type은 `feat`, `fix`, `refactor`, `test`, `docs`, `chore`를 사용합니다.
3. 커밋 형식은 `type: 작업 내용 (#이슈번호)`입니다. 의미 있는 변경 단위로 나눕니다.
4. PR은 작업 브랜치에서 `develop`으로 생성하고 저장소의 [PR 템플릿](.github/pull_request_template.md)을 사용합니다. 제목은 `[Type] 작업 내용`, Issue 연결은 본문에 `Closes #번호` 또는 일부 작업이면 `Related to #번호`로 작성합니다.
5. CI 통과와 최소 1명 이상의 Approve가 원칙입니다. 관련 영역 담당자를 우선 리뷰어로 지정하고, 리뷰는 PR 생성 후 12시간 안에 진행합니다. 공통 코드·타 영역 변경은 수정 전 팀에 공유합니다.
6. Merge 전 수정이 필요한 의견은 반영하거나 리뷰어와 논의해 해결합니다. 충돌은 다른 팀원 코드를 임의로 삭제하지 않고 해결합니다.
7. 병합 방식은 GitHub의 **Merge pull request (Create a merge commit)**입니다. `main`·`develop`에 직접 Push하거나 로컬에서 직접 Merge하지 않습니다.
8. `develop → main` 배포 PR 생성·Merge는 백엔드 팀장이 담당합니다.
9. 작업 중 최신화는 최신 `develop`을 작업 브랜치에 Merge하는 팀 방식을 따릅니다. 공용 브랜치의 force push와 공유 이력의 임의 변경은 금지합니다.

선병합은 후속 작업이 막힌 경우에만 협업 가이드의 조건·확인·공유 절차에 따라 적용합니다. 주요 Entity·연관관계, DB 스키마, 인증·인가, 권한 공통 로직, 공통 응답·예외 처리는 사전 Approve가 필요합니다. 예외를 일반적인 무리뷰 병합 허용으로 해석하지 않습니다.

## 관련 문서

- [프로젝트 Notion](https://app.notion.com/p/4098b259919e8072a7c9f56aa43c977c)
- [기획안](https://app.notion.com/p/4098b259919e80b99b8ce12ad4092e11)
- [백엔드 초기 설정](https://app.notion.com/p/4098b259919e8020a5cde63bc926e326)
- [백엔드 역할 분담](https://app.notion.com/p/4098b259919e80b58c9ffb324dddc1fb)
- [ERD 및 데이터 모델](https://app.notion.com/p/4098b259919e8050a531f3e54d6ab6cb)
- [API 공통규칙](https://app.notion.com/p/4098b259919e8037adf6cc193b4638c5)
- [백엔드 협업 가이드](https://app.notion.com/p/4098b259919e80f79149e110d1ddd180)

Notion 문서는 연결 계정의 접근 권한이 필요할 수 있습니다.
